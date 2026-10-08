package spring.ai.example.spring_ai_demo.biddaily;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 飞书群自定义机器人推送：interactive 卡片 + markdown 元素。
 * <p>
 * 幂等保障：推送是否发生由调用方（批次状态机）控制，本服务只保证
 * “发送成功或抛异常”——失败不标记批次完成，下次执行自动重试。
 * 支持机器人“签名校验”（配置 bid.daily.feishu.secret）。
 */
public class FeishuPushService {

	private static final Logger log = LoggerFactory.getLogger(FeishuPushService.class);
	/** 单张卡片最多展示的公告条数，超出则拆分多条消息（飞书卡片有 30KB 大小限制） */
	private static final int ITEMS_PER_CARD = 20;

	private final BidDailyProperties properties;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	public FeishuPushService(BidDailyProperties properties) {
		this.properties = properties;
	}

	/**
	 * 推送日报卡片。
	 * @return true=实际发生了推送；false=未配置 webhook 而跳过。推送失败抛异常，由调用方决定批次状态。
	 */
	public boolean pushDailyReport(String batchId, List<BidNoticeRecord> relevant, int totalNew) {
		String webhook = properties.getFeishu().getWebhook();
		if (webhook == null || webhook.isBlank()) {
			log.warn("未配置 bid.daily.feishu.webhook，跳过推送（{} 条相关公告已入库）", relevant.size());
			return false;
		}

		String displayDate = batchId.length() == 8
				? batchId.substring(0, 4) + "-" + batchId.substring(4, 6) + "-" + batchId.substring(6)
				: batchId;
		String keywords = String.join("、", properties.getKeywords());

		if (relevant.isEmpty()) {
			sendCard(webhook, "📋 招投标日报 · " + displayDate, List.of(
					"今日未发现与「" + keywords + "」相关的新增公告。",
					"共抓取入库 " + totalNew + " 条，均判定为不相关。"));
			return true;
		}

		int totalCards = (relevant.size() + ITEMS_PER_CARD - 1) / ITEMS_PER_CARD;
		for (int i = 0; i < relevant.size(); i += ITEMS_PER_CARD) {
			List<BidNoticeRecord> part = relevant.subList(i, Math.min(i + ITEMS_PER_CARD, relevant.size()));
			int cardNo = i / ITEMS_PER_CARD + 1;
			String title = "📋 招投标日报 · " + displayDate
					+ (totalCards > 1 ? "（" + cardNo + "/" + totalCards + "）" : "");

			ArrayNode elements = objectMapper.createArrayNode();
			if (cardNo == 1) {
				elements.addObject().put("tag", "markdown")
						.put("content", "**新增相关公告 " + relevant.size() + " 条**（关键词：" + keywords + "）");
			}
			for (int j = 0; j < part.size(); j++) {
				BidNoticeRecord r = part.get(j);
				elements.addObject().put("tag", "hr");
				String md = "**" + (i + j + 1) + ". " + r.getTitle() + "**\n"
						+ (r.getSummary() == null || r.getSummary().isBlank() ? "" : r.getSummary() + "\n")
						+ "📅 " + (r.getPublishDate() == null ? "" : r.getPublishDate())
						+ " | 🏛 " + r.getSource()
						+ " | [查看原文](" + r.getUrl() + ")";
				elements.addObject().put("tag", "markdown").put("content", md);
			}
			sendCardElements(webhook, title, elements);
		}
		return true;
	}

	private void sendCard(String webhook, String title, List<String> markdownLines) {
		ArrayNode elements = objectMapper.createArrayNode();
		for (String line : markdownLines) {
			elements.addObject().put("tag", "markdown").put("content", line);
		}
		sendCardElements(webhook, title, elements);
	}

	private void sendCardElements(String webhook, String title, ArrayNode elements) {
		try {
			ObjectNode body = objectMapper.createObjectNode();
			body.put("msg_type", "interactive");

			String secret = properties.getFeishu().getSecret();
			if (secret != null && !secret.isBlank()) {
				String timestamp = String.valueOf(Instant.now().getEpochSecond());
				body.put("timestamp", timestamp);
				body.put("sign", sign(timestamp, secret));
			}

			ObjectNode card = body.putObject("card");
			ObjectNode header = card.putObject("header");
			header.put("template", "blue");
			header.putObject("title").put("tag", "plain_text").put("content", title);
			card.set("elements", elements);

			HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
					.header("Content-Type", "application/json;charset=utf-8")
					.timeout(Duration.ofSeconds(15))
					.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			JsonNode resp = objectMapper.readTree(response.body());
			int code = resp.path("code").asInt(resp.path("StatusCode").asInt(-1));
			if (response.statusCode() != 200 || code != 0) {
				throw new IllegalStateException("飞书返回错误: HTTP " + response.statusCode() + " " + response.body());
			}
			log.info("飞书推送成功：{}", title);
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("飞书推送失败: " + e.getMessage(), e);
		}
	}

	/** 飞书自定义机器人签名算法：HmacSHA256(key=timestamp+"\n"+secret, data=空字节) 后 Base64 */
	private String sign(String timestamp, String secret) throws Exception {
		String stringToSign = timestamp + "\n" + secret;
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
	}
}
