package spring.ai.example.spring_ai_demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * DashScope 原生协议联网搜索接口（独立实现，不经过 Spring AI ChatClient）。
 * <p>
 * 站点列表（请求参数 sites）非空时：使用服务端硬过滤参数
 * search_options.assigned_site_list + turbo 策略，仅从指定网站检索（实测
 * agent 策略会忽略 assigned_site_list，因此仅在限定站点时使用 turbo）。
 * 站点列表为空时：agent 策略全网检索。
 * <p>
 * qwen3.7-plus 属于多模态模型，原生协议需走 multimodal-generation 端点，
 * 消息 content 为 [{"text": ...}] 数组格式，流式需 X-DashScope-SSE: enable 请求头，
 * 增量输出由 parameters.incremental_output 控制，来源由 output.search_info 返回。
 */
@RestController
@CrossOrigin
public class DashScopeSearchController {

    /** 多模态模型（qwen3.7-plus 等）的联网搜索端点 */
    private static final String API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** DashScope API Key：可通过 dashscope.api-key 单独配置，默认复用 OpenAI 兼容模式那份 */
    @Value("${dashscope.api-key:${spring.ai.openai.chat.api-key:}}")
    private String apiKey;

    /** 搜索使用的模型，默认与主配置一致（多模态模型） */
    @Value("${dashscope.model:qwen3.7-plus}")
    private String model;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 搜索耗时较长且为阻塞 IO，使用虚拟线程避免占用公共线程池 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @GetMapping("/test/dashscope")
    public SseEmitter search(
            @RequestParam(value = "message", defaultValue = "说出10个国家的你好") String message,
            @RequestParam(value = "sites", required = false) String sites,
            HttpServletResponse response) {

        // 强制 UTF-8，避免中文被按 GBK/Latin-1 解码出现乱码
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");

        SseEmitter emitter = new SseEmitter(0L);
        List<String> siteList = parseSites(sites);

        executor.submit(() -> doStream(message, siteList, emitter));
        return emitter;
    }

    /**
     * 解析站点列表：支持中英文逗号、分号、空格分隔，自动去除协议前缀
     */
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
     * 调用 DashScope 原生协议并消费 SSE 流，将增量文本转发给前端
     */
    private void doStream(String message, List<String> siteList, SseEmitter emitter) {
        try {
            ObjectNode body = buildRequestBody(message, siteList);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-SSE", "enable")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (resp.statusCode() != 200) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                sendQuietly(emitter, "**请求失败：** HTTP " + resp.statusCode() + " " + err);
                emitter.complete();
                return;
            }

            List<JsonNode> searchResults = new ArrayList<>();
            String lastEvent = "";

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        lastEvent = line.substring(6).trim();
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        continue;
                    }
                    JsonNode node = objectMapper.readTree(data);

                    // 服务端错误事件：把错误信息透传给前端
                    if ("error".equals(lastEvent)) {
                        sendQuietly(emitter, "**调用出错：** "
                                + node.path("message").asText(data));
                        continue;
                    }

                    // 增量内容：多模态协议下 content 为 [{text:...}] 数组
                    JsonNode content = node.path("output").path("choices").path(0)
                            .path("message").path("content");
                    if (content.isArray()) {
                        for (JsonNode part : content) {
                            String text = part.path("text").asText("");
                            if (!text.isEmpty()) {
                                sendQuietly(emitter, text);
                            }
                        }
                    } else if (content.isTextual() && !content.asText().isEmpty()) {
                        sendQuietly(emitter, content.asText());
                    }

                    // 收集搜索来源（原生协议特有，最后一个非空 search_info 为完整列表）
                    JsonNode results = node.path("output").path("search_info").path("search_results");
                    if (results.isArray() && !results.isEmpty()) {
                        searchResults.clear();
                        results.forEach(searchResults::add);
                    }
                }
            }

            // 回答结束后追加结构化来源列表，前端按 Markdown 渲染
            if (!searchResults.isEmpty()) {
                StringBuilder src = new StringBuilder("\n\n---\n**搜索来源：**\n");
                for (int i = 0; i < searchResults.size(); i++) {
                    JsonNode r = searchResults.get(i);
                    src.append(i + 1).append(". [")
                            .append(r.path("title").asText("来源"))
                            .append("](")
                            .append(r.path("url").asText())
                            .append(")\n");
                }
                sendQuietly(emitter, src.toString());
            }

            emitter.complete();
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().data("调用失败：" + e.getMessage(), MediaType.TEXT_PLAIN));
                emitter.complete();
            } catch (Exception ignored) {
                emitter.completeWithError(e);
            }
        }
    }

    private void sendQuietly(SseEmitter emitter, String text) throws Exception {
        emitter.send(SseEmitter.event().data(text, MediaType.TEXT_PLAIN));
    }

    /**
     * 构建 DashScope 原生协议请求体
     */
    private ObjectNode buildRequestBody(String message, List<String> siteList) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);

        ObjectNode input = body.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode content = userMsg.putArray("content");
        content.addObject().put("text", message);

        ObjectNode parameters = body.putObject("parameters");
        parameters.put("enable_search", true);
        parameters.put("result_format", "message");
        parameters.put("incremental_output", true);

        ObjectNode searchOptions = parameters.putObject("search_options");
        // 注意：enable_source 必须放在 search_options 内部（而非 parameters 顶层），
        // 否则 DashScope 静默忽略，响应中不会返回 search_info 结构化来源
        searchOptions.put("enable_source", true);
        if (siteList.isEmpty()) {
            // 全网检索：agent 策略，模型自主多轮检索
            searchOptions.put("search_strategy", "agent");
        } else {
            // 指定站点检索：turbo 策略 + assigned_site_list 服务端白名单
            searchOptions.put("search_strategy", "turbo");
            ArrayNode assigned = searchOptions.putArray("assigned_site_list");
            siteList.forEach(assigned::add);
        }
        return body;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
