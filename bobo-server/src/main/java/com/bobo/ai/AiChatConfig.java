package com.bobo.ai;

import com.bobo.ai.spi.ChatInvoker;
import com.bobo.ai.spi.NoopChatInvoker;
import lombok.extern.slf4j.Slf4j;
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
 * <p><b>为什么容器里只有一个"动态"调用器</b>：配置现在可以在后台随时改
 * （见 {@link AiSettingsProvider}），而 Spring 的 Bean 一旦创建就不会变。
 * 因此这里暴露一个<b>恒定存在</b>的 {@link DynamicChatInvoker}，它内部持有
 * 真正干活的对象，并在 {@link DynamicChatInvoker#reload()} 时按最新配置重建。
 * 好处是其它组件注入的引用始终有效，改配置不需要重建任何 Bean、更不用重启进程。
 *
 * <p><b>与旧实现的区别</b>：以前是 {@code @ConditionalOnProperty(api-key)} +
 * {@code NoopChatInvoker} 兜底——没 key 时容器里是 noop、有 key 时是 DeepSeek，
 * 配置变更只能重启。现在"有没有 key"变成运行期判断。
 *
 * <p><b>超时是刚需</b>：模型服务抖动时，没有超时的 HTTP 客户端会占着 Tomcat 线程不放，
 * 把整个应用的线程池拖垮——这是外部依赖最典型的故障放大方式。
 * 连接超时与读超时都设在 JDK {@link HttpClient} 上，两个都必须设。
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
     * 唯一的 {@link ChatInvoker} Bean。构造时按当前配置初始化一次，
     * 之后由 {@code AiSettingsService} 在配置保存后调用 {@code reload()} 热更新。
     */
    @Bean
    public DynamicChatInvoker chatInvoker(AiSettingsProvider settingsProvider) {
        return new DynamicChatInvoker(settingsProvider);
    }

    /**
     * 可热重建的模型调用器。
     *
     * <p>线程安全：{@code delegate} 用 volatile 引用整体替换（写少读多），
     * 读路径不加锁；重建期间旧对象仍可服务在途请求，不存在"半个对象"的中间态。
     */
    public static class DynamicChatInvoker implements ChatInvoker {

        private final AiSettingsProvider settingsProvider;

        /** 为空表示当前不可用（等价于 noop） */
        private volatile ChatInvoker delegate;
        private volatile String lastError;

        public DynamicChatInvoker(AiSettingsProvider settingsProvider) {
            this.settingsProvider = settingsProvider;
            reload();
        }

        /**
         * 按最新配置重建内部对象。配置保存后必须调用，否则改动不会生效。
         *
         * @return 重建后是否可用
         */
        public synchronized boolean reload() {
            AiSettingsProvider.RuntimeSettings settings = settingsProvider.current();
            lastError = null;
            if (!settings.usable()) {
                delegate = null;
                return false;
            }
            try {
                delegate = buildSpringAiInvoker(settings);
                log.info("AI 模型客户端已就绪：baseUrl={}, model={}, timeout={}ms, temperature={}",
                        settings.baseUrl(), settings.model(), settings.timeoutMs(), settings.temperature());
                return true;
            } catch (Exception e) {
                // 配置写错（例如 baseUrl 非法）不能让应用挂：标记不可用 + 保留原因供后台展示
                delegate = null;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("按当前配置构建模型客户端失败，AI 将走规则降级：{}", lastError);
                return false;
            }
        }

        /** 最近一次构建失败原因，供后台提示（null 表示无错误） */
        public String lastError() {
            return lastError;
        }

        /**
         * 仅测试用：直接把内部调用器换成替身（{@code MockChatInvoker}）。
         *
         * <p>存在的理由：单元测试要验证门控与降级逻辑，但不能也不该真的构造
         * Spring AI 客户端（需要真 Key 与网络）。有了这个注入点，
         * 测试就不必继承本类去绕父类构造——那种写法很脆，父类一改就全红。
         * 生产代码不会调用它。
         */
        public void setDelegateForTest(ChatInvoker delegate) {
            this.delegate = delegate;
            this.lastError = null;
        }

        @Override
        public String name() {
            ChatInvoker local = delegate;
            return local == null ? "noop" : local.name();
        }

        /**
         * 是否不可用。
         *
         * <p><b>必须同时判断 delegate 自身是不是 noop</b>：本类是个包装器，
         * 内部可能被装入一个 {@code NoopChatInvoker}（例如测试注入、或将来做灰度切换时）。
         * 只看 {@code delegate == null} 会让"内部其实是 noop"对外宣称可用，
         * 于是门控放行 → 调用时才抛异常 → 用户看到的是失败而不是降级结果。
         * 这类"包装器没有透传语义"的问题很隐蔽，因此这里显式取或。
         */
        @Override
        public boolean isNoop() {
            ChatInvoker local = delegate;
            return local == null || local.isNoop();
        }

        @Override
        public ChatReply generate(String systemPrompt, String userPrompt) {
            ChatInvoker local = delegate;
            if (local == null || local.isNoop()) {
                throw new ChatInvokeException("当前没有可用的 AI 模型（未配置 Key、开关已关闭，或客户端未就绪）");
            }
            return local.generate(systemPrompt, userPrompt);
        }

        /** 真正调用 Spring AI 的实现，作用域严格限制在本文件内 */
        private static ChatInvoker buildSpringAiInvoker(AiSettingsProvider.RuntimeSettings settings) {
            Duration timeout = Duration.ofMillis(settings.timeoutMs());

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(timeout);

            var deepSeekApi = org.springframework.ai.deepseek.api.DeepSeekApi.builder()
                    .baseUrl(settings.baseUrl())
                    .apiKey(settings.apiKey())
                    .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                    .webClientBuilder(WebClient.builder())
                    .build();

            var chatModel = org.springframework.ai.deepseek.DeepSeekChatModel.builder()
                    .deepSeekApi(deepSeekApi)
                    .defaultOptions(org.springframework.ai.deepseek.DeepSeekChatOptions.builder()
                            .model(settings.model())
                            .temperature(settings.temperature())
                            .build())
                    .build();

            return new SpringAiDeepSeekInvoker(chatModel);
        }
    }

    /**
     * 把 Spring AI 的 {@code ChatModel} 适配成本项目的 {@link ChatInvoker}。
     * 让 Spring AI 类型的可见范围严格限制在本文件内，业务代码只依赖自定义接口。
     */
    @Slf4j
    static class SpringAiDeepSeekInvoker implements ChatInvoker {

        private final org.springframework.ai.chat.model.ChatModel chatModel;

        SpringAiDeepSeekInvoker(org.springframework.ai.chat.model.ChatModel chatModel) {
            this.chatModel = chatModel;
        }

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
