package spring.ai.example.spring_ai_demo.biddaily;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 招投标日报任务配置（bid.daily.*）。
 * <p>
 * 配置入口：目标站点列表（含各站解析选择器）、过滤关键词、飞书群机器人、
 * 汇总用的 LLM 连接参数（默认在 application.yaml 中引用百炼的 OpenAI 兼容配置）。
 * 注意：本任务自建的 ChatModel 不携带 enable_search 等联网搜索参数，
 * 汇总输入完全来自本地库中已抓取的固定记录，输出才可缓存重放。
 */
@ConfigurationProperties(prefix = "bid.daily")
public class BidDailyProperties {

	/** 总开关：false 时定时任务与手动触发均不执行 */
	private boolean enabled = true;

	/** 定时表达式（Spring 6 位 cron：秒 分 时 日 月 周） */
	private String cron = "0 0 9 * * ?";

	/** 相关性过滤关键词，如：北斗、智能车载 */
	private List<String> keywords = new ArrayList<>(List.of("北斗", "智能车载"));

	/** 每个站点最多翻页数（页面发布日期低于水位线后提前停止） */
	private int maxPagesPerSource = 3;

	/** SQLite 数据库文件路径（库表自动创建） */
	private String dbPath = "./data/bid_daily.db";

	/** 无相关公告时是否推送一条“今日无新增”心跳消息 */
	private boolean pushEmptyReport = true;

	private final Feishu feishu = new Feishu();
	private final Llm llm = new Llm();
	private final List<SourceSite> sources = new ArrayList<>();

	/** 飞书群自定义机器人配置 */
	public static class Feishu {
		/** webhook 地址，留空则只入库不推送 */
		private String webhook = "";
		/** 签名校验密钥（机器人开启“签名校验”时必填），留空表示不加签 */
		private String secret = "";

		public String getWebhook() { return webhook; }
		public void setWebhook(String webhook) { this.webhook = webhook; }
		public String getSecret() { return secret; }
		public void setSecret(String secret) { this.secret = secret; }
	}

	/** 汇总用大模型配置（OpenAI 兼容协议，默认指向阿里云百炼） */
	public static class Llm {
		private String baseUrl = "";
		private String apiKey = "";
		private String model = "qwen-plus";
		/** 低温采样，降低输出随机性 */
		private Double temperature = 0.1;
		/** 提示词版本号：参与结果缓存 key，只有改版本才会重新生成汇总 */
		private String promptVersion = "v1";

		public String getBaseUrl() { return baseUrl; }
		public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
		public String getApiKey() { return apiKey; }
		public void setApiKey(String apiKey) { this.apiKey = apiKey; }
		public String getModel() { return model; }
		public void setModel(String model) { this.model = model; }
		public Double getTemperature() { return temperature; }
		public void setTemperature(Double temperature) { this.temperature = temperature; }
		public String getPromptVersion() { return promptVersion; }
		public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
	}

	/** 一个目标站点的列表页配置（jsoup 选择器，均为相对列表项的相对选择器） */
	public static class SourceSite {
		/** 站点名称（存储、展示、水位线键） */
		private String name = "default";
		/** 第一页地址（部分站点首页为 index.htm，与分页模板不同） */
		private String firstPageUrl;
		/** 分页地址模板，{page} 为页码占位符；缺省时只抓第一页 */
		private String pageUrlTemplate;
		/** 页面字符集，政府类站点常见 GBK */
		private String encoding = "UTF-8";
		/** 列表项选择器，如 ul.c_list_bid li */
		private String itemSelector;
		/** 标题元素选择器，取 title 属性或文本 */
		private String titleSelector = "a";
		/** 链接元素选择器 */
		private String linkSelector = "a";
		/** 日期元素选择器；留空则在整个列表项文本中匹配日期 */
		private String dateSelector = "em";
		private boolean enabled = true;

		public String getName() { return name; }
		public void setName(String name) { this.name = name; }
		public String getFirstPageUrl() { return firstPageUrl; }
		public void setFirstPageUrl(String firstPageUrl) { this.firstPageUrl = firstPageUrl; }
		public String getPageUrlTemplate() { return pageUrlTemplate; }
		public void setPageUrlTemplate(String pageUrlTemplate) { this.pageUrlTemplate = pageUrlTemplate; }
		public String getEncoding() { return encoding; }
		public void setEncoding(String encoding) { this.encoding = encoding; }
		public String getItemSelector() { return itemSelector; }
		public void setItemSelector(String itemSelector) { this.itemSelector = itemSelector; }
		public String getTitleSelector() { return titleSelector; }
		public void setTitleSelector(String titleSelector) { this.titleSelector = titleSelector; }
		public String getLinkSelector() { return linkSelector; }
		public void setLinkSelector(String linkSelector) { this.linkSelector = linkSelector; }
		public String getDateSelector() { return dateSelector; }
		public void setDateSelector(String dateSelector) { this.dateSelector = dateSelector; }
		public boolean isEnabled() { return enabled; }
		public void setEnabled(boolean enabled) { this.enabled = enabled; }
	}

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public String getCron() { return cron; }
	public void setCron(String cron) { this.cron = cron; }
	public List<String> getKeywords() { return keywords; }
	public void setKeywords(List<String> keywords) { this.keywords = keywords; }
	public int getMaxPagesPerSource() { return maxPagesPerSource; }
	public void setMaxPagesPerSource(int maxPagesPerSource) { this.maxPagesPerSource = maxPagesPerSource; }
	public String getDbPath() { return dbPath; }
	public void setDbPath(String dbPath) { this.dbPath = dbPath; }
	public boolean isPushEmptyReport() { return pushEmptyReport; }
	public void setPushEmptyReport(boolean pushEmptyReport) { this.pushEmptyReport = pushEmptyReport; }
	public Feishu getFeishu() { return feishu; }
	public Llm getLlm() { return llm; }
	public List<SourceSite> getSources() { return sources; }
}
