package spring.ai.example.spring_ai_demo.biddaily;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性采集器：直接抓取目标站点公告列表页（jsoup 解析），不经过大模型 web_search。
 * <p>
 * 幂等保障：
 * <ul>
 *   <li>水位线 —— 每个站点记住已处理到的最新发布日期，列表按时间倒序翻页，
 *       整页都早于水位线即提前终止，重跑不会重扫历史页；</li>
 *   <li>发布日期不早于水位线的记录保留（同日新增靠存储层唯一键去重兜底）；</li>
 *   <li>同一页面解析结果完全确定，杜绝了搜索引擎召回的随机性。</li>
 * </ul>
 */
public class BidSourceCrawler {

	private static final Logger log = LoggerFactory.getLogger(BidSourceCrawler.class);
	private static final String USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
	private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})");

	private final BidDailyProperties properties;
	private final BidDailyStore store;
	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	public BidSourceCrawler(BidDailyProperties properties, BidDailyStore store) {
		this.properties = properties;
		this.store = store;
	}

	/** 抓取所有启用站点，返回待入库记录（此处不去重，去重由存储层唯一键保证） */
	public List<BidNoticeRecord> crawlAll(String batchId) {
		List<BidNoticeRecord> all = new ArrayList<>();
		for (BidDailyProperties.SourceSite site : properties.getSources()) {
			if (!site.isEnabled()) {
				continue;
			}
			try {
				all.addAll(crawlSite(site, batchId));
			} catch (Exception e) {
				// 单站点失败不阻断其他站点，也不阻断后续汇总/推送
				log.error("采集站点[{}]失败: {}", site.getName(), e.getMessage(), e);
			}
		}
		return all;
	}

	private List<BidNoticeRecord> crawlSite(BidDailyProperties.SourceSite site, String batchId) throws Exception {
		LocalDate watermark = parseDate(store.getWatermark(site.getName()));
		List<BidNoticeRecord> acc = new ArrayList<>();
		LocalDate maxSeen = null;

		for (int page = 1; page <= properties.getMaxPagesPerSource(); page++) {
			String pageUrl = pageUrl(site, page);
			if (pageUrl == null) {
				break;
			}
			Document doc = fetchPage(pageUrl, site.getEncoding());
			Elements items = doc.select(site.getItemSelector());
			if (items.isEmpty()) {
				log.info("站点[{}]第{}页无匹配列表项，停止翻页", site.getName(), page);
				break;
			}
			boolean allBelowWatermark = true;
			for (Element item : items) {
				BidNoticeRecord record = parseItem(site, item, batchId);
				if (record == null) {
					continue;
				}
				if (maxSeen == null || record.getPublishDate().isAfter(maxSeen)) {
					maxSeen = record.getPublishDate();
				}
				// 发布日期不早于水位线的记录保留（同日多次执行靠唯一键去重）
				if (watermark == null || !record.getPublishDate().isBefore(watermark)) {
					acc.add(record);
					allBelowWatermark = false;
				}
			}
			if (allBelowWatermark) {
				// 列表按发布时间倒序，整页都早于水位线即可提前终止翻页
				log.info("站点[{}]第{}页全部早于水位线{}，停止翻页", site.getName(), page, watermark);
				break;
			}
		}

		if (maxSeen != null) {
			store.updateWatermarkIfNewer(site.getName(), maxSeen);
		}
		log.info("站点[{}]抓取完成：待入库 {} 条（水位线：{}）", site.getName(), acc.size(), watermark);
		return acc;
	}

	/** 第一页优先使用 firstPageUrl，其余页用模板 {page} 替换；无模板则只抓第一页 */
	private String pageUrl(BidDailyProperties.SourceSite site, int page) {
		if (page == 1 && site.getFirstPageUrl() != null && !site.getFirstPageUrl().isBlank()) {
			return site.getFirstPageUrl();
		}
		if (site.getPageUrlTemplate() == null || site.getPageUrlTemplate().isBlank()) {
			return page == 1 ? site.getFirstPageUrl() : null;
		}
		return site.getPageUrlTemplate().replace("{page}", String.valueOf(page));
	}

	private Document fetchPage(String url, String encoding) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", USER_AGENT)
				.timeout(Duration.ofSeconds(20))
				.GET()
				.build();
		HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("HTTP " + response.statusCode() + " " + url);
		}
		// 政府类站点多为 GBK 编码，按配置字符集解码
		String html = new String(response.body(), Charset.forName(encoding));
		return Jsoup.parse(html, url);
	}

	private BidNoticeRecord parseItem(BidDailyProperties.SourceSite site, Element item, String batchId) {
		Element titleEl = item.select(site.getTitleSelector()).first();
		Element linkEl = item.select(site.getLinkSelector()).first();
		if (titleEl == null || linkEl == null) {
			return null;
		}
		String title = titleEl.hasAttr("title") ? titleEl.attr("title").trim() : titleEl.text().trim();
		String url = linkEl.absUrl("href");
		if (url == null || url.isBlank()) {
			url = linkEl.attr("href");
		}
		url = url.split("#")[0].trim();
		if (title.isEmpty() || url.isEmpty()) {
			return null;
		}

		BidNoticeRecord record = new BidNoticeRecord();
		record.setSource(site.getName());
		record.setUrlHash(BidDailyStore.sha256(url));
		record.setTitle(title);
		record.setUrl(url);
		record.setBatchId(batchId);

		Element dateEl = (site.getDateSelector() == null || site.getDateSelector().isBlank())
				? null : item.select(site.getDateSelector()).first();
		String dateText = dateEl != null ? dateEl.text() : item.text();
		LocalDate date = extractDate(dateText);
		if (date == null) {
			// 无法解析发布日期时按当天处理，靠唯一键去重兜底
			date = LocalDate.now();
		}
		record.setPublishDate(date);
		return record;
	}

	private LocalDate extractDate(String text) {
		if (text == null) {
			return null;
		}
		Matcher m = DATE_PATTERN.matcher(text);
		if (!m.find()) {
			return null;
		}
		try {
			return LocalDate.of(Integer.parseInt(m.group(1)),
					Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
		} catch (Exception e) {
			return null;
		}
	}

	private LocalDate parseDate(String iso) {
		if (iso == null || iso.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(iso);
		} catch (Exception e) {
			return null;
		}
	}
}
