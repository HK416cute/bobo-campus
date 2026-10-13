package com.bobo.ai;

import com.bobo.ai.spi.ChatInvoker;
import com.bobo.ai.spi.NoopChatInvoker;
import com.bobo.config.props.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Spring AI + DeepSeek 装配。
 *
 * <p><b>为什么手写 Bean 而不是靠 starter 自动配置</b>：本项目只用到「一次问答」这一条链路，
 * 不需要 tool calling / embedding / 向量库。手写装配让依赖边界一目了然：
 * 全工程的 Spring AI 类型只出现在这一个类里，其余代码只依赖 {@link ChatInvoker}。
 * 将来换厂商（通义 / 智谱 / 自建网关）只需新增一个同样形状的 {@code @Bean}。
 *
 * <p><b>超时是刚需</b>：模型服务抖动时，没有超时的 HTTP 客户端会占着 Tomcat 线程不放，
 * 把整个应用的线程池拖垮——这是外部依赖最典型的故障放大方式。
 * 连接超时与读超时都设在 JDK {@link HttpClient} 上（建连 + 等待响应两个阶段），
 * 两个都必须设，只设一个挡不住另一半。
 *
 * <p><b>依赖提示</b>：{@code DeepSeekApi} 的构造器要求一个 {@code WebClient.Builder}，
 * 而 {@code spring-ai-deepseek} 自身不带 HTTP 客户端，因此 pom 里显式引了 {@code spring-webflux}
 * （只引它，不引 {@code spring-boot-starter-webflux}，避免多带一个用不到的 Reactor Netty 服务器）。
 *
 * @author bobo
 */
@Slf4j
@Configuration
public class AiChatConfig {

    /**
     * DeepSeek 调用器。
     *
     * <p>仅在配置了非空 {@code bobo.ai.api-key} 时装配；
     * 未配置时本 Bean 不存在，由 {@link #noopChatInvoker()} 顶上。
     *
     * <p>注意这里<b>不判断</b> {@code bobo.ai.enabled}：总开关属于运行期门控，
     * 统一由 {@link AiAvailability} 判定。把「装配」和「是否允许调用」两件事分开，
     * 运行期才能靠配置热切换（例如先用规则跑通演示，再打开 AI），而不必重启改 Bean 条件。
     */
    @Bean
    @ConditionalOnProperty(prefix = "bobo.ai", name = "api-key")
    public ChatInvoker deepSeekChatInvoker(AiProperties properties) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            // 理论上到不了这里（条件已拦住空值），防御式返回 noop，避免用空 key 去发请求
            return new NoopChatInvoker();
        }
        Duration timeout = Duration.ofMillis(properties.getTimeoutMs());

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        var deepSeekApi = org.springframework.ai.deepseek.api.DeepSeekApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder())
                .build();

        var chatModel = org.springframework.ai.deepseek.DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(org.springframework.ai.deepseek.DeepSeekChatOptions.builder()
                        .model(properties.getModel())
                        .temperature(properties.getTemperature())
                        .build())
                .build();

        log.info("已装配 DeepSeek 对话模型：baseUrl={}, model={}, timeout={}ms",
                properties.getBaseUrl(), properties.getModel(), properties.getTimeoutMs());
        return new SpringAiDeepSeekInvoker(chatModel);
    }

    /**
     * 兜底实现：未配置 api-key 时装配，保证 {@link ChatInvoker} 始终可注入。
     * 它是「AI 未启用」这一状态的显式表达，而不是靠 null 判断。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(ChatInvoker.class)
    public ChatInvoker noopChatInvoker() {
        return new NoopChatInvoker();
    }

    /**
     * 把 Spring AI 的 {@code ChatModel} 适配成本项目的 {@link ChatInvoker}。
     *
     * <p>放在本配置类内部作为静态嵌套类，是为了让「Spring AI 类型」的影响范围
     * 严格限制在 {@code com.bobo.ai} 的这一个文件里——其它所有业务代码
     * 连 import 都碰不到 Spring AI，测试时可以整体替换。
     */
    @Slf4j
    @RequiredArgsConstructor
    static class SpringAiDeepSeekInvoker implements ChatInvoker {

        private final org.springframework.ai.chat.model.ChatModel chatModel;

        @Override
        public String name() {
            return "spring-ai-deepseek";
        }

        @Override
        public boolean isNoop() {
            return false;
        }

        @Override
        public ChatReply generate(String systemPrompt, String userPrompt) {
            try {
                var prompt = new org.springframework.ai.chat.prompt.Prompt(
                        java.util.List.of(
                                new org.springframework.ai.chat.messages.SystemMessage(systemPrompt),
                                new org.springframework.ai.chat.messages.UserMessage(userPrompt)));
                var response = chatModel.call(prompt);
                if (response == null || response.getResult() == null
                        || response.getResult().getOutput() == null) {
                    throw new ChatInvokeException("模型返回空结果");
                }
                // token 用量来自响应元数据，是成本统计的唯一来源；
                // 供应商未返回 usage 时保持 null，不能编造成 0（0 会让成本报表失真）
                Integer promptTokens = null;
                Integer completionTokens = null;
                var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
                if (usage != null) {
                    promptTokens = usage.getPromptTokens();
                    completionTokens = usage.getCompletionTokens();
                }
                return new ChatReply(response.getResult().getOutput().getText(),
                        promptTokens, completionTokens);
            } catch (ChatInvokeException e) {
                throw e;
            } catch (Exception e) {
                // 统一包装成 ChatInvokeException，让 AiExecutor 只需捕获一种异常
                throw new ChatInvokeException("DeepSeek 调用失败：" + e.getMessage(), e);
            }
        }
    }
}
