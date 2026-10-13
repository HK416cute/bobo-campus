package com.bobo.ai;

import com.bobo.config.AiAsyncConfig;
import com.bobo.domain.entity.AiCallLog;
import com.bobo.mapper.AiCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * AI 调用流水的写入组件。
 *
 * <p><b>为什么单独一个类</b>：{@code @Async} 靠代理生效，
 * <b>同一个类里方法互调会绕过代理，异步注解直接失效</b>——
 * 这是最容易写出「看起来异步、实际同步」的地方。
 * 把异步写入单独成 Bean 后，调用方从外部进代理，行为才与注解一致。
 *
 * <p><b>为什么 userId 要当参数传进来</b>：{@code AuthContext} 是请求级 ThreadLocal，
 * 而异步写入跑在 {@code bobo-ai-*} 线程池里 —— 在新线程读 {@code AuthContext} 只能拿到 null，
 * 结果是流水表 user_id 全为空，每日配额统计（按 user_id 计数）直接失效，
 * 表现为「配额永远用不完」。所以调用方必须在<b>原线程</b>取下 userId 再传进来。
 *
 * @author bobo
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiCallLogWriter {

    private final AiCallLogMapper callLogMapper;
    private final AiSettingsProvider settingsProvider;

    /**
     * 同步写入，返回自增主键。调用方需要 {@code logId}（例如反馈表要回指是哪次模型输出）时用。
     */
    public Long insert(AiCallLog entity) {
        try {
            callLogMapper.insert(entity);
            return entity.getLogId();
        } catch (Exception e) {
            // 记日志失败不能反过来影响业务：可观测性让位于可用性
            log.warn("写入 AI 调用流水失败：scene={}, status={}, cause={}",
                    entity.getScene(), entity.getStatus(), e.getMessage());
            return null;
        }
    }

    /**
     * 异步写入，用于纯统计场景（调用方不需要 logId）。
     * 必须从<b>外部</b>调用才能真正异步，见类注释。
     */
    @Async(AiAsyncConfig.AI_TASK_EXECUTOR)
    public void insertAsync(AiCallLog entity) {
        insert(entity);
    }

    /**
     * 由本组件统一构造流水实体，避免各场景各拼一份字段（漏字段就会算错成本）。
     *
     * <p>注意这里<b>不负责</b>填 {@code model} 与 {@code estimatedCost}：
     * 模型名与单价都取自运行时配置，而依赖方向是「Recorder → Writer」，
     * 让 Writer 反过来依赖 Recorder 会形成构造期循环。
     * 由调用方（{@link AiCallRecorder}）在拿到实体后补上这两项。
     *
     * @param userId 调用方在原线程取到的当前用户，可为 null（匿名）
     */
    public AiCallLog build(String scene, String status, String degradeReason, String errorMsg,
                           String inputHash, String outputHash, long latencyMs,
                           Integer promptTokens, Integer completionTokens, String provider,
                           Long userId) {
        AiCallLog entity = new AiCallLog();
        entity.setScene(scene);
        entity.setUserId(userId);
        entity.setProvider(provider);
        entity.setInputHash(inputHash);
        entity.setOutputHash(outputHash);
        entity.setPromptTokens(promptTokens);
        entity.setCompletionTokens(completionTokens);
        entity.setTotalTokens(sum(promptTokens, completionTokens));
        entity.setLatencyMs(latencyMs);
        entity.setStatus(status);
        entity.setDegradeReason(truncate(degradeReason, 255));
        entity.setErrorMsg(truncate(errorMsg, 500));
        entity.setCreateTime(LocalDateTime.now());
        return entity;
    }

    private Integer sum(Integer a, Integer b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
