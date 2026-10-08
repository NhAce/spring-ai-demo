package spring.ai.example.spring_ai_demo.biddaily;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM 过滤汇总层：输入固定（库中本批次 NEW 状态的记录），输出缓存（bid_llm_cache）。
 * <p>
 * 幂等保障：
 * <ul>
 *   <li>输入是库内记录的确定序列化 JSON，不依赖任何搜索结果；</li>
 *   <li>缓存 key = SHA-256(提示词版本 + 关键词 + 记录集)，命中即不再调用 API；</li>
 *   <li>大模型只负责“相关性判断 + 每条一句话摘要”，飞书消息排版由代码确定性生成；</li>
 *   <li>大模型漏答的记录保持 NEW 状态，下次执行自动重试。</li>
 * </ul>
 */
public class BidSummarizeService {

	private static final Logger log = LoggerFactory.getLogger(BidSummarizeService.class);
	/** 单次送入大模型的最大记录数，超出则分批 */
	private static final int CHUNK_SIZE = 40;

	private static final String SYSTEM_PROMPT = """
			你是招投标公告分析助手。用户会提供当日新抓取的招标公告列表（JSON 数组，字段：id、source、title、publishDate）。
			请对每条记录完成两件事：
			1. 判断该公告与给定业务关键词是否相关（只依据标题和来源判断，不确定时判为不相关，不要编造信息）；
			2. 用不超过 40 字的中文概括采购标的与采购人，作为 summary。
			输出要求：只输出一个 JSON 数组，不要输出任何解释文字、前后缀或 Markdown 代码块；
			数组元素形如 {"id":1,"relevant":true,"summary":"..."}，每条输入记录必须恰好对应一个输出元素。
			""";

	private final BidDailyProperties properties;
	private final BidDailyStore store;
	private final ChatClient chatClient;
	private final ObjectMapper objectMapper = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	public BidSummarizeService(BidDailyProperties properties, BidDailyStore store, ChatClient chatClient) {
		this.properties = properties;
		this.store = store;
		this.chatClient = chatClient;
	}

	/** 对批次内所有 NEW 记录做相关性过滤与摘要，返回处理条数（已处理的自动跳过，天然幂等） */
	public int summarizeBatch(String batchId) {
		List<BidNoticeRecord> records = store.findByBatchAndStatus(batchId, "NEW");
		if (records.isEmpty()) {
			return 0;
		}
		log.info("批次[{}]待汇总 {} 条", batchId, records.size());
		int processed = 0;
		for (int i = 0; i < records.size(); i += CHUNK_SIZE) {
			processed += summarizeChunk(records.subList(i, Math.min(i + CHUNK_SIZE, records.size())));
		}
		return processed;
	}

	private int summarizeChunk(List<BidNoticeRecord> chunk) {
		String cacheKey = buildCacheKey(chunk);
		String json = store.getCachedLlmResult(cacheKey);
		if (json == null) {
			String content = chatClient.prompt()
					.system(SYSTEM_PROMPT)
					.user(buildUserPrompt(chunk))
					.call()
					.content();
			json = extractJsonArray(content);
			store.putLlmCache(cacheKey, json);
			log.info("大模型汇总完成，{} 条（缓存 key={}...）", chunk.size(), cacheKey.substring(0, 12));
		} else {
			log.info("大模型汇总缓存命中，{} 条（key={}...）", chunk.size(), cacheKey.substring(0, 12));
		}

		List<ItemSummary> summaries = parseSummaries(json);
		Map<Long, ItemSummary> byId = summaries.stream()
				.collect(Collectors.toMap(ItemSummary::id, s -> s, (a, b) -> a));
		int processed = 0;
		for (BidNoticeRecord record : chunk) {
			ItemSummary summary = byId.get(record.getId());
			if (summary == null) {
				// 大模型漏答的记录保持 NEW，下次执行自动重试
				log.warn("大模型未返回记录[{}]（{}）的结果，保持待处理", record.getId(), record.getTitle());
				continue;
			}
			store.markSummarized(record.getId(), summary.relevant(),
					summary.summary() == null ? "" : summary.summary());
			processed++;
		}
		return processed;
	}

	private String buildUserPrompt(List<BidNoticeRecord> chunk) {
		ArrayNode array = objectMapper.createArrayNode();
		for (BidNoticeRecord r : chunk) {
			ObjectNode node = array.addObject();
			node.put("id", r.getId());
			node.put("source", r.getSource());
			node.put("title", r.getTitle());
			node.put("publishDate", r.getPublishDate() == null ? "" : r.getPublishDate().toString());
		}
		return "业务关键词：" + String.join("、", properties.getKeywords())
				+ "\n\n公告列表：\n" + array
				+ "\n\n请严格按约定输出 JSON 数组。";
	}

	/** 缓存 key = 提示词版本 + 关键词 + 记录集内容 的哈希：输入不变则命中，绝不重复调用 API */
	private String buildCacheKey(List<BidNoticeRecord> chunk) {
		String ids = chunk.stream()
				.map(r -> r.getId() + ":" + r.getUrlHash())
				.collect(Collectors.joining(","));
		return BidDailyStore.sha256(properties.getLlm().getPromptVersion()
				+ "|" + String.join(",", properties.getKeywords()) + "|" + ids);
	}

	/** 容错提取 JSON 数组：去掉可能的 Markdown 代码块围栏，截取首个 [ 到最后一个 ] */
	private String extractJsonArray(String content) {
		if (content == null || content.isBlank()) {
			throw new IllegalStateException("大模型返回内容为空");
		}
		String s = content.trim()
				.replaceAll("^```(json)?\\s*", "")
				.replaceAll("\\s*```$", "");
		int start = s.indexOf('[');
		int end = s.lastIndexOf(']');
		if (start < 0 || end <= start) {
			throw new IllegalStateException("大模型未返回 JSON 数组: "
					+ s.substring(0, Math.min(200, s.length())));
		}
		return s.substring(start, end + 1);
	}

	private List<ItemSummary> parseSummaries(String json) {
		try {
			return objectMapper.readValue(json, new TypeReference<List<ItemSummary>>() { });
		} catch (Exception e) {
			throw new IllegalStateException("解析大模型汇总结果失败: " + json, e);
		}
	}

	/** 大模型单条输出：记录 id、相关性、摘要 */
	public record ItemSummary(long id, boolean relevant, String summary) { }
}
