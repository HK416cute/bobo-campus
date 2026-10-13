package com.bobo.ai;

import com.bobo.ai.AiChatConfig.DynamicChatInvoker;
import com.bobo.ai.AiSettingsProvider.RuntimeSettings;
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
 *   <li>配置层可用（总开关打开且 api-key 非空）——配置来自数据库/环境变量，可运行期变更；</li>
 *   <li>调用器不是空实现（即已按当前配置成功构建出模型客户端）。</li>
 * </ol>
 *
 * <p>注意本类<b>不缓存</b>结论：配置随时可能被后台改动，
 * 每次判断都读一次快照（volatile 读，无锁无 DB 访问），保证门控结果始终是最新的。
 *
 * @author bobo
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAvailability {

    private final AiSettingsProvider settingsProvider;
    private final DynamicChatInvoker chatInvoker;

    /** 模型是否可用于本次调用 */
    public boolean modelUsable() {
        return settingsProvider.current().usable() && !chatInvoker.isNoop();
    }

    /** 不可用时的原因，用于日志与接口回显（前端可提示「AI 未启用」） */
    public String unavailableReason() {
        RuntimeSettings settings = settingsProvider.current();
        if (!settings.enabled()) {
            return "AI 功能已关闭（可在管理后台「AI 运营 → 模型配置」中开启）";
        }
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            return "未配置模型 API Key（可在管理后台「AI 运营 → 模型配置」中填写）";
        }
        String buildError = chatInvoker.lastError();
        if (buildError != null) {
            return "当前配置无法构建模型客户端：" + buildError;
        }
        if (chatInvoker.isNoop()) {
            return "模型客户端未就绪，已降级为规则模式";
        }
        return null;
    }

    /** 当前是否运行在降级模式（前端据此显示「规则推算」标签） */
    public boolean degraded() {
        return !modelUsable();
    }

    public String modelName() {
        return settingsProvider.current().model();
    }

    /**
     * 启动后打一行日志说明 AI 处于哪种形态。
     * 排查「为什么没走模型」时，看这一行就够了，不用去翻配置。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logStatus() {
        RuntimeSettings settings = settingsProvider.current();
        if (modelUsable()) {
            log.info("AI 能力已启用：model={}, baseUrl={}, timeout={}ms, 单用户限额 {} QPS / 每日 {} 次",
                    settings.model(), settings.baseUrl(), settings.timeoutMs(),
                    settings.userQps(), settings.dailyQuota());
        } else {
            log.warn("AI 能力降级为规则模式：{}", unavailableReason());
        }
        String warning = settingsProvider.storageWarning();
        if (warning != null) {
            log.warn(warning);
        }
    }
}
