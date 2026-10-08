package spring.ai.example.spring_ai_demo.biddaily;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 招投标数据的 SQLite 持久层，整套幂等设计的落点：
 * <ul>
 *   <li>bid_notice 的 UNIQUE(source, url_hash) + INSERT OR IGNORE —— 重复抓取直接丢弃，
 *       同一天任务重跑多少次，库内数据都收敛一致；</li>
 *   <li>bid_watermark —— 每个站点已处理到的最新发布日期，翻页提前终止，避免全量翻页；</li>
 *   <li>bid_batch —— 批次（= 日期）是否已推送，已 PUSHED 的批次不再重复推送；</li>
 *   <li>bid_llm_cache —— 大模型汇总结果缓存，相同输入（记录集 + 提示词版本 + 关键词）
 *       命中缓存即不再调用 API，把大模型变成可重放的“纯函数”。</li>
 * </ul>
 * 单机低频任务，全部方法 synchronized 串行化，避免 SQLite 文件锁冲突。
 */
public class BidDailyStore {

	private static final Logger log = LoggerFactory.getLogger(BidDailyStore.class);

	private static final String SELECT_COLUMNS =
			"id, source, url_hash, title, url, publish_date, batch_id, status, relevant, summary";

	private final String jdbcUrl;

	public BidDailyStore(String dbPath) {
		try {
			Path path = Path.of(dbPath).toAbsolutePath().normalize();
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			this.jdbcUrl = "jdbc:sqlite:" + path;
			initSchema();
			log.info("招投标日报 SQLite 存储就绪: {}", path);
		} catch (Exception e) {
			throw new IllegalStateException("初始化 SQLite 存储失败: " + dbPath, e);
		}
	}

	private void initSchema() throws SQLException {
		try (Connection conn = open(); Statement st = conn.createStatement()) {
			st.execute("""
					CREATE TABLE IF NOT EXISTS bid_notice (
					  id INTEGER PRIMARY KEY AUTOINCREMENT,
					  source TEXT NOT NULL,
					  url_hash TEXT NOT NULL,
					  title TEXT NOT NULL,
					  url TEXT,
					  publish_date TEXT,
					  batch_id TEXT NOT NULL,
					  status TEXT NOT NULL DEFAULT 'NEW',
					  relevant INTEGER NOT NULL DEFAULT 0,
					  summary TEXT,
					  created_at TEXT NOT NULL,
					  UNIQUE(source, url_hash)
					)""");
			st.execute("CREATE TABLE IF NOT EXISTS bid_watermark (source TEXT PRIMARY KEY, publish_date TEXT NOT NULL)");
			st.execute("CREATE TABLE IF NOT EXISTS bid_batch (batch_id TEXT PRIMARY KEY, status TEXT NOT NULL, pushed_at TEXT)");
			st.execute("CREATE TABLE IF NOT EXISTS bid_llm_cache (cache_key TEXT PRIMARY KEY, result TEXT NOT NULL, created_at TEXT NOT NULL)");
		}
	}

	private Connection open() throws SQLException {
		return DriverManager.getConnection(jdbcUrl);
	}

	/** URL 的 SHA-256，作为内容指纹参与唯一键 */
	public static String sha256(String value) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest(value.getBytes(StandardCharsets.UTF_8))) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * 幂等落库：UNIQUE(source, url_hash) 冲突的记录直接忽略。
	 * 通过批次内总数前后差值计算实际新增条数（不依赖 executeBatch 的返回语义）。
	 */
	public synchronized int insertIgnore(List<BidNoticeRecord> records, String batchId) {
		if (records == null || records.isEmpty()) {
			return 0;
		}
		try (Connection conn = open()) {
			long before = countByBatch(conn, batchId);
			try (PreparedStatement ps = conn.prepareStatement(
					"INSERT OR IGNORE INTO bid_notice(source, url_hash, title, url, publish_date, batch_id, status, relevant, created_at) "
							+ "VALUES(?,?,?,?,?,?,'NEW',0,?)")) {
				String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
				for (BidNoticeRecord r : records) {
					ps.setString(1, r.getSource());
					ps.setString(2, r.getUrlHash());
					ps.setString(3, r.getTitle());
					ps.setString(4, r.getUrl());
					ps.setString(5, r.getPublishDate() == null ? null : r.getPublishDate().toString());
					ps.setString(6, batchId);
					ps.setString(7, now);
					ps.addBatch();
				}
				ps.executeBatch();
			}
			return (int) (countByBatch(conn, batchId) - before);
		} catch (SQLException e) {
			throw new IllegalStateException("公告落库失败", e);
		}
	}

	private long countByBatch(Connection conn, String batchId) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM bid_notice WHERE batch_id=?")) {
			ps.setString(1, batchId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getLong(1) : 0;
			}
		}
	}

	/** 查询批次内指定状态的记录（如 NEW：待 LLM 汇总） */
	public synchronized List<BidNoticeRecord> findByBatchAndStatus(String batchId, String status) {
		return query("SELECT " + SELECT_COLUMNS + " FROM bid_notice WHERE batch_id=? AND status=? ORDER BY id",
				batchId, status);
	}

	/**
	 * 查询批次内判定为相关的记录。
	 * @param includePushed false 时只取未推送的（FILTERED），true 时包含已推送的（用于强制重推）
	 */
	public synchronized List<BidNoticeRecord> findRelevantByBatch(String batchId, boolean includePushed) {
		String sql = "SELECT " + SELECT_COLUMNS + " FROM bid_notice WHERE batch_id=? AND relevant=1"
				+ (includePushed ? "" : " AND status='FILTERED'")
				+ " ORDER BY publish_date DESC, id DESC";
		return query(sql, batchId);
	}

	private List<BidNoticeRecord> query(String sql, String... params) {
		List<BidNoticeRecord> list = new ArrayList<>();
		try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				ps.setString(i + 1, params[i]);
			}
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					BidNoticeRecord r = new BidNoticeRecord();
					r.setId(rs.getLong("id"));
					r.setSource(rs.getString("source"));
					r.setUrlHash(rs.getString("url_hash"));
					r.setTitle(rs.getString("title"));
					r.setUrl(rs.getString("url"));
					String d = rs.getString("publish_date");
					r.setPublishDate(d == null ? null : LocalDate.parse(d));
					r.setBatchId(rs.getString("batch_id"));
					r.setStatus(rs.getString("status"));
					r.setRelevant(rs.getInt("relevant") == 1);
					r.setSummary(rs.getString("summary"));
					list.add(r);
				}
			}
			return list;
		} catch (SQLException e) {
			throw new IllegalStateException("查询公告失败", e);
		}
	}

	/** 记录 LLM 处理结果：NEW → FILTERED */
	public synchronized void markSummarized(long id, boolean relevant, String summary) {
		try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(
				"UPDATE bid_notice SET status='FILTERED', relevant=?, summary=? WHERE id=?")) {
			ps.setInt(1, relevant ? 1 : 0);
			ps.setString(2, summary);
			ps.setLong(3, id);
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new IllegalStateException("更新汇总结果失败", e);
		}
	}

	public synchronized boolean isBatchPushed(String batchId) {
		try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(
				"SELECT status FROM bid_batch WHERE batch_id=?")) {
			ps.setString(1, batchId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() && "PUSHED".equals(rs.getString(1));
			}
		} catch (SQLException e) {
			throw new IllegalStateException("查询批次状态失败", e);
		}
	}

	/** 批次置为 PUSHED，并把相关记录状态推进到 PUSHED（幂等：重复调用无副作用） */
	public synchronized void markBatchPushed(String batchId) {
		String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		try (Connection conn = open()) {
			try (PreparedStatement ps = conn.prepareStatement(
					"INSERT OR IGNORE INTO bid_batch(batch_id, status) VALUES(?, 'RUNNING')")) {
				ps.setString(1, batchId);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = conn.prepareStatement(
					"UPDATE bid_batch SET status='PUSHED', pushed_at=? WHERE batch_id=?")) {
				ps.setString(1, now);
				ps.setString(2, batchId);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = conn.prepareStatement(
					"UPDATE bid_notice SET status='PUSHED' WHERE batch_id=? AND status='FILTERED' AND relevant=1")) {
				ps.setString(1, batchId);
				ps.executeUpdate();
			}
		} catch (SQLException e) {
			throw new IllegalStateException("标记批次推送状态失败", e);
		}
	}

	public synchronized String getWatermark(String source) {
		try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(
				"SELECT publish_date FROM bid_watermark WHERE source=?")) {
			ps.setString(1, source);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		} catch (SQLException e) {
			throw new IllegalStateException("查询水位线失败", e);
		}
	}

	/** 水位线只在出现更新日期时前移（ISO 日期字符串比较即时间序比较） */
	public synchronized void updateWatermarkIfNewer(String source, LocalDate date) {
		try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO bid_watermark(source, publish_date) VALUES(?,?) "
						+ "ON CONFLICT(source) DO UPDATE SET publish_date=excluded.publish_date "
						+ "WHERE excluded.publish_date > bid_watermark.publish_date")) {
			ps.setString(1, source);
			ps.setString(2, date.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new IllegalStateException("更新水位线失败", e);
		}
	}

	public synchronized String getCachedLlmResult(String cacheKey) {
		try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(
				"SELECT result FROM bid_llm_cache WHERE cache_key=?")) {
			ps.setString(1, cacheKey);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		} catch (SQLException e) {
			throw new IllegalStateException("查询 LLM 缓存失败", e);
		}
	}

	public synchronized void putLlmCache(String cacheKey, String result) {
		try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(
				"INSERT OR REPLACE INTO bid_llm_cache(cache_key, result, created_at) VALUES(?,?,?)")) {
			ps.setString(1, cacheKey);
			ps.setString(2, result);
			ps.setString(3, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new IllegalStateException("写入 LLM 缓存失败", e);
		}
	}
}
