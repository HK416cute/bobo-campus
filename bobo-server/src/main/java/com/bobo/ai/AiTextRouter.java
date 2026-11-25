package com.bobo.ai;

import com.bobo.ai.spi.ChatInvoker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 文本能力门面：给撮合、RAG 等新场景一个稳定的调用入口。
 *
 * <p>升级后所有文本能力（发布助手、检索解析、审核、运营、撮合、RAG）都走同一家
 * DeepSeek，同一个 {@link ChatInvoker}、同一套门控/限流/配额/流水。这一层存在的原因是：
 * <ul>
 *   <li>业务场景不直接依赖 {@link AiExecutor} 的重载细节，将来换模型只改这里；</li>
 *   <li>状态接口需要回答「当前是 deepseek 还是 rule」，与 {@link AiExecutor} 的关注点分开。</li>
 * </ul>
 *
 * <p>没有模型时 {@link #execute} 仍会返回规则结果（由 {@link AiExecutor} 保证），
 * 所以撮合与 RAG 不需要写 if-null。
 *
 * @author bobo
 */
@Component
@RequiredArgsConstructor
public class AiTextRouter {

    private final AiExecutor aiExecutor;
    private final ChatInvoker chatInvoker;

    /** 当前是否有可用的 DeepSeek 文本模型 */
    public boolean available() {
        return !chatInvoker.isNoop();
    }

    /** 当前文本模式：deepseek / rule，仅用于状态展示 */
    public String mode() {
        return available() ? "deepseek" : "rule";
    }

    public <T> AiOutcome<T> execute(String scene,
                                    String systemPrompt,
                                    String userPrompt,
                                    Function<String, T> parser,
                                    Supplier<T> degrade) {
        return aiExecutor.execute(scene, systemPrompt, userPrompt, parser, degrade);
    }
}
