package spring.ai.example.spring_ai_demo.biddaily;

import java.time.LocalDate;

/** 一条招标公告（对应表 bid_notice 的一行） */
public class BidNoticeRecord {

	private long id;
	/** 来源站点名称 */
	private String source;
	/** 公告 URL 的 SHA-256，与 source 组成唯一键（幂等去重的依据） */
	private String urlHash;
	private String title;
	private String url;
	private LocalDate publishDate;
	/** 所属批次（yyyyMMdd） */
	private String batchId;
	/** NEW → FILTERED → PUSHED */
	private String status = "NEW";
	/** 大模型判定的相关性 */
	private boolean relevant;
	/** 大模型生成的一句话摘要 */
	private String summary;

	public long getId() { return id; }
	public void setId(long id) { this.id = id; }
	public String getSource() { return source; }
	public void setSource(String source) { this.source = source; }
	public String getUrlHash() { return urlHash; }
	public void setUrlHash(String urlHash) { this.urlHash = urlHash; }
	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }
	public String getUrl() { return url; }
	public void setUrl(String url) { this.url = url; }
	public LocalDate getPublishDate() { return publishDate; }
	public void setPublishDate(LocalDate publishDate) { this.publishDate = publishDate; }
	public String getBatchId() { return batchId; }
	public void setBatchId(String batchId) { this.batchId = batchId; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public boolean isRelevant() { return relevant; }
	public void setRelevant(boolean relevant) { this.relevant = relevant; }
	public String getSummary() { return summary; }
	public void setSummary(String summary) { this.summary = summary; }
}
