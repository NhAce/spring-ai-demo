package spring.ai.example.spring_ai_demo.biddaily;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 招投标日报配置类：
 * <ul>
 *   <li>@EnableScheduling 挂在本配置类上，不改动启动类；</li>
 *   <li>为汇总任务单独构建 ChatClient：复用百炼的 OpenAI 兼容端点，但不携带
 *       enable_search 等联网搜索参数（输入固定才可缓存重放）。ChatModel/ChatClient
 *       均不注册为 Spring Bean，避免顶替 spring-ai 自动配置的 OpenAiChatModel，
 *       影响既有接口（/test/openai 等）。</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(BidDailyProperties.class)
public class BidDailyConfig {

	@Bean
	public BidDailyStore bidDailyStore(BidDailyProperties properties) {
		return new BidDailyStore(properties.getDbPath());
	}

	@Bean
	public BidSourceCrawler bidSourceCrawler(BidDailyProperties properties, BidDailyStore store) {
		return new BidSourceCrawler(properties, store);
	}

	@Bean
	public BidSummarizeService bidSummarizeService(BidDailyProperties properties, BidDailyStore store) {
		return new BidSummarizeService(properties, store, buildChatClient(properties.getLlm()));
	}

	@Bean
	public FeishuPushService feishuPushService(BidDailyProperties properties) {
		return new FeishuPushService(properties);
	}

	@Bean
	public BidDailyReportTask bidDailyReportTask(BidDailyProperties properties,
			BidDailyStore store,
			BidSourceCrawler crawler,
			BidSummarizeService summarizeService,
			FeishuPushService pushService) {
		return new BidDailyReportTask(properties, store, crawler, summarizeService, pushService);
	}

	@Bean
	public BidDailyController bidDailyController(BidDailyReportTask task) {
		return new BidDailyController(task);
	}

	private ChatClient buildChatClient(BidDailyProperties.Llm llm) {
		OpenAiChatOptions options = OpenAiChatOptions.builder()
				.baseUrl(llm.getBaseUrl())
				.apiKey(llm.getApiKey())
				.model(llm.getModel())
				.temperature(llm.getTemperature())
				.build();
		OpenAiChatModel chatModel = OpenAiChatModel.builder().options(options).build();
		return ChatClient.builder(chatModel).build();
	}
}
