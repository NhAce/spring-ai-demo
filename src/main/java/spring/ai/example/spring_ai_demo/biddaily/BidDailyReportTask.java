package spring.ai.example.spring_ai_demo.biddaily;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 招投标日报编排任务（定时 + 手动触发），批次 = 日期。四步流水线全部幂等可重跑：
 * <ol>
 *   <li>采集：翻页抓取各站点列表页直到水位线，INSERT OR IGNORE 唯一键去重落库；</li>
 *   <li>汇总：对批次内 NEW 记录调用大模型（固定输入，结果缓存，命中不调 API）；</li>
 *   <li>推送：批次未 PUSHED 才组装卡片推送飞书，成功后落 PUSHED；</li>
 *   <li>重跑：任一步失败后重新执行，从断点续跑——已入库的不重采、已汇总的不重调、已推送的不重发。</li>
 * </ol>
 */
public class BidDailyReportTask {

	private static final Logger log = LoggerFactory.getLogger(BidDailyReportTask.class);
	public static final DateTimeFormatter BATCH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final BidDailyProperties properties;
	private final BidDailyStore store;
	private final BidSourceCrawler crawler;
	private final BidSummarizeService summarizeService;
	private final FeishuPushService pushService;
	/** 防止定时触发与手动触发并发执行 */
	private final AtomicBoolean running = new AtomicBoolean(false);

	public BidDailyReportTask(BidDailyProperties properties, BidDailyStore store, BidSourceCrawler crawler,
			BidSummarizeService summarizeService, FeishuPushService pushService) {
		this.properties = properties;
		this.store = store;
		this.crawler = crawler;
		this.summarizeService = summarizeService;
		this.pushService = pushService;
	}

	/** 执行结果（手动触发接口的返回体 / 日志摘要） */
	public record RunResult(String batchId, int crawled, int inserted, int summarized,
			int relevant, boolean pushed, String message) { }

	@Scheduled(cron = "${bid.daily.cron:0 0 9 * * ?}")
	public void scheduled() {
		if (!properties.isEnabled()) {
			return;
		}
		try {
			run(LocalDate.now(), false);
		} catch (Exception e) {
			log.error("招投标日报定时任务执行失败", e);
		}
	}

	public RunResult run(LocalDate date, boolean forcePush) {
		String batchId = date.format(BATCH_FORMATTER);
		if (!properties.isEnabled()) {
			return new RunResult(batchId, 0, 0, 0, 0, false, "bid.daily.enabled=false，任务未执行");
		}
		if (!running.compareAndSet(false, true)) {
			return new RunResult(batchId, 0, 0, 0, 0, false, "上一批次仍在执行中，本次触发已忽略");
		}
		// 断点计数：失败时随异常带出，便于接口返回已完成的进度
		int crawledCount = 0;
		int insertedCount = 0;
		int summarizedCount = 0;
		try {
			// 步骤1：确定性采集（水位线增量翻页）+ 唯一键去重落库
			List<BidNoticeRecord> fetched = crawler.crawlAll(batchId);
			crawledCount = fetched.size();
			insertedCount = store.insertIgnore(fetched, batchId);

			// 步骤2：LLM 过滤汇总（只处理 NEW 记录；缓存命中则不调 API）
			summarizedCount = summarizeService.summarizeBatch(batchId);

			// 步骤3：推送（批次状态机：已 PUSHED 的批次默认跳过）
			boolean alreadyPushed = store.isBatchPushed(batchId);
			List<BidNoticeRecord> relevantAll = store.findRelevantByBatch(batchId, true);
			boolean pushed = false;
			String message;

			if (relevantAll.isEmpty() && !properties.isPushEmptyReport()) {
				if (!alreadyPushed) {
					store.markBatchPushed(batchId);
				}
				message = "无相关公告且 push-empty-report=false，批次标记完成，不推送";
			} else if (alreadyPushed && !forcePush) {
				message = "该批次已推送过，跳过重复推送（force=true 可强制重推）";
			} else {
				List<BidNoticeRecord> toPush = alreadyPushed
						? relevantAll
						: store.findRelevantByBatch(batchId, false);
				boolean sent = pushService.pushDailyReport(batchId, toPush, insertedCount);
				if (sent) {
					store.markBatchPushed(batchId);
					pushed = true;
					message = "推送成功";
				} else {
					message = "未配置飞书 webhook，跳过推送（数据已入库，配置后可手动重跑）";
				}
			}

			RunResult result = new RunResult(batchId, crawledCount, insertedCount, summarizedCount,
					relevantAll.size(), pushed, message);
			log.info("批次[{}]执行完成：抓取 {} / 新增 {} / 汇总 {} / 相关 {} / 推送 {} - {}",
					batchId, result.crawled(), result.inserted(), result.summarized(),
					result.relevant(), pushed, message);
			return result;
		} catch (Exception e) {
			log.error("批次[{}]执行失败（断点进度：抓取 {} / 新增 {} / 已汇总 {}）",
					batchId, crawledCount, insertedCount, summarizedCount, e);
			throw new BatchRunException(batchId, crawledCount, insertedCount, summarizedCount, e);
		} finally {
			running.set(false);
		}
	}

	/** 携带断点进度的执行失败异常：手动触发接口据此返回已完成的部分结果 */
	public static class BatchRunException extends RuntimeException {

		private final int crawled;
		private final int inserted;
		private final int summarized;

		public BatchRunException(String batchId, int crawled, int inserted, int summarized, Throwable cause) {
			super("批次[" + batchId + "]执行失败: " + cause.getMessage(), cause);
			this.crawled = crawled;
			this.inserted = inserted;
			this.summarized = summarized;
		}

		public int getCrawled() {
			return crawled;
		}

		public int getInserted() {
			return inserted;
		}

		public int getSummarized() {
			return summarized;
		}
	}
}
