package spring.ai.example.spring_ai_demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 方案三：Spring AI Tool Calling + 阿里云联网搜索 API（完全新增，不依赖/不修改现有代码）
 *
 * 与另外两种方案的本质区别：
 *  - enable_search / assigned_site_list：搜索在百炼服务端执行，一次请求完成
 *  - 本方案：模型只"下指令"（返回工具调用请求），真正执行搜索的是下面 WebSearchTool 里的本地代码，
 *    搜索结果再回传给模型生成回答 —— 多轮网络往返，但搜索来源、过滤、后处理完全由客户端控制
 *
 * 主模型：在 @PostConstruct 中用 OpenAiChatModel.builder() 构建的独立实例（百炼 qwen3.7-plus，
 * 不带 yaml 全局的 extra-body 联网搜索参数，保证是"纯 Tool Calling"——模型自身不联网）。
 */
@RestController
@CrossOrigin
public class ToolSearchController {

    /** 工具内部调用的阿里云联网搜索 API（DashScope 原生 text-generation 端点，qwen-plus 文本模型） */
    private static final String SEARCH_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatClient chatClient;

    @Value("${dashscope.api-key:${spring.ai.openai.chat.api-key:}}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    /** 主模型（负责推理、决定何时调用工具、生成最终回答） */
    @Value("${toolcall.chat-model:qwen3.7-plus}")
    private String chatModel;

    /** 工具内负责联网检索的模型（与主模型分离，仅用来执行搜索） */
    @Value("${toolcall.search-model:qwen-plus}")
    private String searchModel;

    /**
     * 构建独立的模型实例：指向同一个百炼模型，但不带 yaml 里全局配置的
     * extra-body 联网搜索参数 —— 否则服务端搜索会同时生效，就不是"纯 Tool Calling"演示了
     */
    @PostConstruct
    void init() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(chatModel)
                .build();
        this.chatClient = ChatClient.builder(OpenAiChatModel.builder().options(options).build()).build();
    }

    @GetMapping("/test/toolcall")
    public SseEmitter toolCall(
            @RequestParam(value = "message", defaultValue = "说出10个国家的你好") String message,
            @RequestParam(value = "sites", required = false) String sites,
            HttpServletResponse response) {

        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");

        SseEmitter emitter = new SseEmitter(0L);
        List<String> siteList = parseSites(sites);

        // 每次请求创建独立的工具实例，携带本次的站点白名单（无并发问题）
        Flux<String> content = chatClient.prompt()
                .system("你是一个乐于助人的AI助手。回答时请使用规范的Markdown格式：标题用#、列表用-或1.、代码块用```包裹并注明语言。"
                        + "调用搜索工具后，请基于工具返回的结果作答，并引用其中的链接。")
                .user(message)
                .tools(new WebSearchTool(apiKey, searchModel, siteList))
                .stream().content();

        content.subscribe(
                token -> {
                    try {
                        emitter.send(SseEmitter.event().data(token, MediaType.TEXT_PLAIN));
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );

        return emitter;
    }

    private List<String> parseSites(String sites) {
        if (sites == null || sites.isBlank()) {
            return List.of();
        }
        return Arrays.stream(sites.split("[,，;；\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.replaceFirst("^https?://", ""))
                .collect(Collectors.toList());
    }

    /**
     * 联网搜索工具：由大模型自主决定何时调用、传什么关键词。
     * 真正的搜索 HTTP 请求在这里（你的代码）执行，结果回传给模型。
     */
    static class WebSearchTool {

        private final String apiKey;
        private final String searchModel;
        private final List<String> sites;

        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        WebSearchTool(String apiKey, String searchModel, List<String> sites) {
            this.apiKey = apiKey;
            this.searchModel = searchModel;
            this.sites = sites;
        }

        @Tool(description = "联网搜索工具：当用户问题涉及实时或最新信息（如新闻、招投标、天气、价格、政策）时调用。"
                + "返回搜索结果的标题和链接列表。")
        public String webSearch(@ToolParam(description = "提炼用户问题得到的搜索关键词，简洁明确") String query) {
            try {
                ObjectNode body = buildRequestBody(query);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(SEARCH_API_URL))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> resp =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) {
                    return "搜索失败：HTTP " + resp.statusCode() + " " + resp.body();
                }

                JsonNode root = mapper.readTree(resp.body());
                List<JsonNode> results = new ArrayList<>();
                root.path("output").path("search_info").path("search_results")
                        .forEach(results::add);

                // 站点白名单：客户端硬过滤（即使服务端未按白名单检索，这里也保证只保留指定域名）
                if (!sites.isEmpty()) {
                    results = results.stream().filter(this::matchSites).collect(Collectors.toList());
                }

                if (results.isEmpty()) {
                    return "未搜索到相关结果。";
                }
                StringBuilder sb = new StringBuilder("搜索结果：\n");
                for (int i = 0; i < results.size(); i++) {
                    JsonNode r = results.get(i);
                    sb.append(i + 1).append(". ").append(r.path("title").asText("无标题"))
                            .append(" — ").append(r.path("url").asText("")).append("\n");
                }
                return sb.toString();
            } catch (Exception e) {
                return "搜索失败：" + e.getMessage();
            }
        }

        private boolean matchSites(JsonNode result) {
            String url = result.path("url").asText("");
            String host = url.replaceFirst("^https?://", "").replaceAll("/.*$", "");
            return sites.stream().anyMatch(host::endsWith);
        }

        private ObjectNode buildRequestBody(String query) {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", searchModel);
            ObjectNode input = body.putObject("input");
            ArrayNode messages = input.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", query);
            ObjectNode parameters = body.putObject("parameters");
            parameters.put("enable_search", true);
            parameters.put("result_format", "message");
            // 搜索模型只需快速返回搜索结果，截断其正文生成（实测 32s -> 3s）
            parameters.put("max_tokens", 64);
            ObjectNode searchOptions = parameters.putObject("search_options");
            searchOptions.put("search_strategy", "turbo");
            // enable_source 必须放在 search_options 内部，否则响应不返回 search_info
            searchOptions.put("enable_source", true);
            if (!sites.isEmpty()) {
                // 服务端站点白名单（turbo 策略下生效）
                ArrayNode assigned = searchOptions.putArray("assigned_site_list");
                sites.forEach(assigned::add);
            }
            return body;
        }
    }
}
