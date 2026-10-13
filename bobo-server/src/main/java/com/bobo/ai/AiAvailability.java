package com.bobo.ai;

import com.bobo.ai.spi.ChatInvoker;
import com.bobo.config.props.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * AI 可用性门控：所有 AI 场景在调用模型前都必须先问这里。
 *
 * <p>两个条件同时满足才算「模型可用」：
 * <ol>
 *   <li>配置层可用（{@code bobo.ai.enabled=true} 且 api-key 非空）；</li>
 *   <li>容器里真的存在一个可用的 {@link ChatInvoker}（不是 {@link ChatInvoker#isNoop()}）。</li>
 * </ol>
 * 任一不满足即降级为规则实现。这层判断把「没有 key」变成一种<b>正常的运行形态</b>而不是异常。
 *
 * @author bobo
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAvailability {

    private final AiProperties properties;
    private final ChatInvoker chatInvoker;

    /** 模型是否可用于本次调用 */
    public boolean modelUsable() {
        return properties.usable() && !chatInvoker.isNoop();
    }

    /** 不可用时的原因，用于日志与接口回显（前端可提示「AI 未启用」） */
    public String unavailableReason() {
        if (!properties.isEnabled()) {
            return "AI 功能已通过 bobo.ai.enabled=false 关闭";
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return "未配置 bobo.ai.api-key，已降级为规则模式";
        }
        if (chatInvoker.isNoop()) {
            return "未加载到 Spring AI 对话模型，已降级为规则模式";
        }
        return null;
    }

    /** 当前是否运行在降级模式（前端据此显示「规则推算」标签） */
    public boolean degraded() {
        return !modelUsable();
    }

    public String modelName() {
        return properties.getModel();
    }

    /**
     * 启动后打一行日志说明 AI 处于哪种形态。
     * 排查「为什么没走模型」时，看这一行就够了，不用去翻配置。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logStatus() {
        if (modelUsable()) {
            log.info("AI 能力已启用：model={}, baseUrl={}, timeout={}ms, 单用户限额 {} QPS / 每日 {} 次",
                    properties.getModel(), properties.getBaseUrl(), properties.getTimeoutMs(),
                    properties.getRate().getUserQps(), properties.getRate().getDailyQuota());
        } else {
            log.warn("AI 能力降级为规则模式：{}", unavailableReason());
        }
    }
}
