package com.bobo.ai.vision;

import com.bobo.ai.AiSettingsProvider;
import com.bobo.ai.spi.ChatInvoker;
import com.bobo.ai.spi.VisionInvoker;
import com.bobo.config.props.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 多模态调用器：直连 OpenAI 兼容的 {@code /chat/completions}，发送图文混合消息。
 *
 * <p><b>为什么不用 Spring AI 的 DeepSeek starter 处理图片</b>：
 * 本项目锁定的 spring-ai 1.0.0 对多模态消息块的支持不完整，而 DeepSeek 官方
 * {@code deepseek-flash} 已原生支持图像输入（base64 / URL / Files API）。
 * 手写这一层可以精确控制：
 * <ul>
 *   <li>{@code image_url} 内容块与 {@code detail} 级别；</li>
 *   <li>{@code response_format=json_object}，把「定价必须返回 JSON」变成服务端约束；</li>
 *   <li>{@code safety_check}（DeepSeek 全家桶应用 API 之一），对输入图片与输出做基础风控；</li>
 *   <li>超时、错误码与 usage（含上下文缓存命中数）的解析。</li>
 * </ul>
 *
 * <p><b>Key / Base URL / 模型都来自现有 DeepSeek 配置</b>（后台可改，环境变量兜底），
 * 不引入第二家厂商，也不需要单独的视觉 Key。
 *
 * <p>没有 Key、关闭多模态或调用失败时，{@link #available()} 为 false 或抛
 * {@link ChatInvoker.ChatInvokeException}，定价层统一降级为规则估价。
 *
 * @author bobo
 */
@Slf4j
public class DeepSeekVisionInvoker implements VisionInvoker {

    private final AiSettingsProvider settingsProvider;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DeepSeekVisionInvoker(AiSettingsProvider settingsProvider, AiProperties properties,
                                 ObjectMapper objectMapper) {
        this.settingsProvider = settingsProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = buildClient(properties);
    }

    private RestClient buildClient(AiProperties props) {
        try {
            Duration timeout = Duration.ofMillis(props.getVisionTimeoutMs());
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(timeout)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(timeout);
            return RestClient.builder().requestFactory(factory).build();
        } catch (Exception e) {
            log.warn("构建 DeepSeek 多模态 HTTP 客户端失败，定价将走规则降级：{}", e.getMessage());
            return null;
        }
    }

    @Override
    public String name() {
        return "deepseek-vision";
    }

    @Override
    public String model() {
        String configured = properties.getVisionModel();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return settingsProvider.current().model();
    }

    @Override
    public boolean available() {
        AiSettingsProvider.RuntimeSettings settings = settingsProvider.current();
        return properties.isVisionEnabled() && settings.usable() && restClient != null;
    }

    @Override
    public String unavailableReason() {
        AiSettingsProvider.RuntimeSettings settings = settingsProvider.current();
        if (!properties.isVisionEnabled()) {
            return "多模态能力已关闭（bobo.ai.vision-enabled=false）";
        }
        if (!settings.enabled()) {
            return "AI 功能已关闭（可在管理后台「AI 运营 → 模型配置」中开启）";
        }
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            return "未配置 DeepSeek API Key（环境变量 BOBO_AI_API_KEY，或管理后台模型配置）";
        }
        if (restClient == null) {
            return "DeepSeek 多模态客户端构建失败，请检查 bobo.ai.base-url / timeout-ms";
        }
        return null;
    }

    @Override
    public ChatInvoker.ChatReply generate(String systemPrompt, String userPrompt,
                                          List<ImageAttachment> images, int maxTokens) {
        if (!available()) {
            throw new ChatInvoker.ChatInvokeException(unavailableReason());
        }
        AiSettingsProvider.RuntimeSettings settings = settingsProvider.current();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("messages", buildMessages(systemPrompt, userPrompt, images));
        body.put("max_tokens", Math.max(64, Math.min(maxTokens, properties.getVisionMaxTokens())));
        body.put("temperature", properties.getTemperature());
        // JSON 输出：定价/评估必须返回结构化 JSON，服务端约束比提示词更硬
        body.put("response_format", Map.of("type", "json_object"));
        // DeepSeek 全家桶应用 API 的模型安全能力：对输入图片/文本与输出做基础风控
        body.put("safety_check", Map.of("level", "high"));

        String raw;
        long start = System.currentTimeMillis();
        try {
            raw = restClient.post()
                    .uri(endpoint(settings.baseUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + settings.apiKey().trim())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            String detail = e.getResponseBodyAsString();
            if (detail != null && detail.length() > 300) {
                detail = detail.substring(0, 300);
            }
            throw new ChatInvoker.ChatInvokeException(
                    "DeepSeek HTTP " + e.getStatusCode().value() + "：" + detail, e);
        } catch (Exception e) {
            throw new ChatInvoker.ChatInvokeException("DeepSeek 多模态调用失败：" + e.getMessage(), e);
        }

        log.debug("DeepSeek 多模态调用完成：model={}, images={}, latency={}ms",
                model(), images == null ? 0 : images.size(), System.currentTimeMillis() - start);
        return parse(raw);
    }

    private List<Map<String, Object>> buildMessages(String systemPrompt, String userPrompt,
                                                    List<ImageAttachment> images) {
        List<Map<String, Object>> messages = new ArrayList<>(2);
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", systemPrompt == null ? "" : systemPrompt);
        messages.add(system);

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", userPrompt == null ? "" : userPrompt));
        if (images != null) {
            for (ImageAttachment image : images) {
                String dataUrl = "data:" + image.mediaType() + ";base64,"
                        + Base64.getEncoder().encodeToString(image.data());
                Map<String, Object> imageUrl = new LinkedHashMap<>();
                imageUrl.put("url", dataUrl);
                // high 保留细节：成色判断需要看划痕/磕碰，省这点 token 得不偿失
                imageUrl.put("detail", "high");
                content.add(Map.of("type", "image_url", "image_url", imageUrl));
            }
        }
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", content);
        messages.add(user);
        return messages;
    }

    /**
     * 接口地址：兼容三种 base-url 写法——官方根路径、以 /v1 结尾、或完整
     * {@code /chat/completions}。这样换代理/网关时不用改代码。
     */
    private String endpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    /** 解析 OpenAI 兼容响应，并记录上下文缓存命中数（DeepSeek 硬盘缓存自动生效） */
    private ChatInvoker.ChatReply parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String content = message.hasNonNull("content") ? message.get("content").asText() : null;
            if (content == null || content.isBlank()) {
                String reason = choice.path("finish_reason").asText("");
                throw new ChatInvoker.ChatInvokeException(
                        "DeepSeek 未返回文本内容（finish_reason=" + reason + "，可能被安全策略拦截）");
            }
            JsonNode usage = root.path("usage");
            Integer promptTokens = usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
            Integer completionTokens = usage.hasNonNull("completion_tokens")
                    ? usage.get("completion_tokens").asInt() : null;
            JsonNode details = usage.path("prompt_tokens_details");
            if (details.hasNonNull("prompt_cache_hit_tokens")) {
                log.debug("DeepSeek 上下文缓存命中 {} tokens / 总计 {} tokens",
                        details.get("prompt_cache_hit_tokens").asInt(),
                        promptTokens == null ? 0 : promptTokens);
            }
            return new ChatInvoker.ChatReply(content, promptTokens, completionTokens);
        } catch (ChatInvoker.ChatInvokeException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatInvoker.ChatInvokeException("DeepSeek 响应解析失败：" + e.getMessage(), e);
        }
    }
}
