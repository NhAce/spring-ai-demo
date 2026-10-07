package spring.ai.example.spring_ai_demo;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

@RestController
@CrossOrigin
public class TestController {

    private static final String BASE_SYSTEM_PROMPT = "你是一个乐于助人的AI助手。回答时请使用规范的Markdown格式：标题用#、列表用-或1.、代码块用```包裹并注明语言、引用用>。不要将整个回答包裹在代码块中。";

    private final ChatClient chatClient;

    /**
     * 默认限定的站点列表（逗号分隔），在 application.yaml 中配置 search.sites，留空表示全网搜索
     */
    @Value("${search.sites:}")
    private String defaultSites;

//    public TestController(DeepSeekChatModel deepSeekChatModel) {
//        this.chatClient = ChatClient.builder(deepSeekChatModel).build();
//    }

    public TestController(OpenAiChatModel openAiChatModel) {
        this.chatClient = ChatClient.builder(openAiChatModel).build();
    }

    /**
     * 构建系统提示词：站点列表不为空时，指示模型用 site: 运算符将联网搜索限定在指定网站
     */
    private String buildSystemPrompt(String sites) {
        String effective = (sites != null && !sites.isBlank()) ? sites : defaultSites;
        if (effective == null || effective.isBlank()) {
            return BASE_SYSTEM_PROMPT;
        }
        String siteList = Arrays.stream(effective.split("[,，;；\\s]+"))
                .filter(s -> !s.isBlank())
                .map(String::trim)
                .map(s -> s.replaceFirst("^https?://", ""))
                .collect(Collectors.joining("、"));
        if (siteList.isEmpty()) {
            return BASE_SYSTEM_PROMPT;
        }
        return BASE_SYSTEM_PROMPT
                + "联网搜索时，请使用site:运算符将检索范围严格限定在以下网站：" + siteList
                + "。只允许引用上述网站的信息；如果这些网站检索不到相关内容，请明确说明没有找到，不要引用任何其他网站的内容。";
    }

    @GetMapping("/test/deepseek")
    public SseEmitter testDeepseek(
            @RequestParam(value = "message", defaultValue = "说出10个国家的你好") String message,
            HttpServletResponse response) {

        // 强制 UTF-8，避免中文被按 GBK/Latin-1 解码出现乱码
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");

        SseEmitter emitter = new SseEmitter(0L);

        Flux<String> content = chatClient.prompt()
                .system("你是一个乐于助人的AI助手。回答时请使用规范的Markdown格式：标题用#、列表用-或1.、代码块用```包裹并注明语言、引用用>。不要将整个回答包裹在代码块中。")
                .user(message).stream().content();

        content.subscribe(
                token -> {
                    try {
                        emitter.send(SseEmitter.event().data(token, MediaType.TEXT_PLAIN));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );

        return emitter;
    }


    @GetMapping("/test/openai")
    public SseEmitter testOpenai(
            @RequestParam(value = "message", defaultValue = "说出10个国家的你好") String message,
            @RequestParam(value = "sites", required = false) String sites,
            HttpServletResponse response) {

        // 强制 UTF-8，避免中文被按 GBK/Latin-1 解码出现乱码
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");

        SseEmitter emitter = new SseEmitter(0L);

        Flux<String> content = chatClient.prompt()
                .system(buildSystemPrompt(sites))
                .user(message).stream().content();

        content.subscribe(
                token -> {
                    try {
                        emitter.send(SseEmitter.event().data(token, MediaType.TEXT_PLAIN));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );

        return emitter;
    }
}
