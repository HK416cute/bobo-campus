package com.bobo.ai;

/**
 * AI 场景执行结果：值 + 来源 + 是否降级。
 *
 * <p>把「来源」显式带回给调用方，是为了让前端能诚实标注
 * 「AI 生成」与「规则推算」——用户看到的结论必须能追溯到它是谁给的，
 * 这也是排查线上问题时区分「模型变笨」和「降级生效」的唯一线索。
 *
 * @param value       结果值，必不为 null（降级实现保证有值）
 * @param source      {@link AiExecutor#SOURCE_AI} 或 {@link AiExecutor#SOURCE_RULE}
 * @param degraded    是否发生了降级
 * @param degradeNote 降级原因，可直接展示给用户（未降级时为 null）
 * @param logId       关联的调用流水ID，未调用模型时为 null
 * @param latencyMs   本次模型调用耗时，未调用模型时为 0
 * @param <T>         结果类型
 * @author bobo
 */
public record AiOutcome<T>(T value,
                           String source,
                           boolean degraded,
                           String degradeNote,
                           Long logId,
                           long latencyMs) {

    /** 模型成功返回 */
    public static <T> AiOutcome<T> ai(T value, Long logId, long latencyMs) {
        return new AiOutcome<>(value, AiExecutor.SOURCE_AI, false, null, logId, latencyMs);
    }

    /** 降级为规则结果 */
    public static <T> AiOutcome<T> rule(T value, String note, Long logId) {
        return new AiOutcome<>(value, AiExecutor.SOURCE_RULE, true, note, logId, 0L);
    }
}
