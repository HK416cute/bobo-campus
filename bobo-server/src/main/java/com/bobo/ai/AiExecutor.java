package com.bobo.ai;

import com.bobo.ai.spi.ChatInvoker;
import com.bobo.domain.entity.AiCallLog;
import com.bobo.mapper.AiCallLogMapper;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * AI 场景统一执行器：所有 AI 能力都必须经过它，而不是各自直接调用模型。
 *
 * <p>把「调用模型」这件事的所有横切关注点收在一处，业务场景只写两件事：
 * <b>提示词</b>和<b>解析函数</b>；外加一条规则兜底实现（无模型时也保证有结果）。
 *
 * <pre>
 * AiOutcome&lt;ItemDraft&gt; outcome = aiExecutor.execute(
 *         AiCallLog.SCENE_PUBLISH_DRAFT,
 *         SYSTEM_PROMPT, userPrompt,
 *         raw -&gt; jsonParser.parse(raw, ItemDraft.class),   // 解析 + 校验，非法返回 null 触发降级
 *         () -&gt; rules.draftFrom(text));                    // 降级实现
 * outcome.value();     // 一定有值：要么模型给的，要么规则给的
 * outcome.source();    // "ai" 还是 "rule"，前端据此打标签
 * </pre>
 *
 * <p>固定流程：<b>门控 → 限流 → 配额 → 调用（超时/重试）→ 解析校验 → 落流水</b>，
 * 任一步失败都走同一条降级路径，因此「模型挂了」在业务代码里不存在特殊分支。
 *
 * @author bobo
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiExecutor {

    /** 来源：模型生成 */
    public static final String SOURCE_AI = "ai";
    /** 来源：本地规则（降级） */
    public static final String SOURCE_RULE = "rule";

    private final AiSettingsProvider settingsProvider;
    private final AiAvailability availability;
    private final AiRateLimiter rateLimiter;
    private final AiCallRecorder recorder;
    private final ChatInvoker chatInvoker;
    private final AiCallLogMapper callLogMapper;

    /**
     * 结构化 JSON 输出约定。统一追加到系统提示词，避免每个场景各写一套格式要求
     * （格式要求分散 = 解析逻辑分散 = 降级口径不一致）。
     */
    private static final String JSON_CONTRACT = """

            输出要求（必须严格遵守）：
            1. 只输出一个 JSON 对象，不要输出 Markdown 代码块、解释文字或多余前后缀；
            2. 所有字符串使用双引号，不要使用单引号，不要出现尾随逗号；
            3. 字段名与下方约定完全一致，缺失信息用 null，不要编造；
            4. 无法判断时宁可留空，也不要输出推测性内容。
            """;

    /**
     * 执行一次 AI 场景调用。
     *
     * @param scene        场景标识，写入流水用于分场景统计
     * @param systemPrompt 角色与约束
     * @param userPrompt   本次输入
     * @param parser       模型原文 → 结果对象；解析或校验不通过时返回 {@code null} 触发降级
     * @param degrade      降级实现，必须保证不抛异常
     * @param <T>          结果类型
     * @return 结果与来源，见 {@link AiOutcome}
     */
    public <T> AiOutcome<T> execute(String scene,
                                    String systemPrompt,
                                    String userPrompt,
                                    Function<String, T> parser,
                                    java.util.function.Supplier<T> degrade) {
        Long userId = AuthContext.userIdOrNull();

        // 1) 门控：没有 key / 显式关闭 —— 不调用、不写流水（否则流水表会被无意义的降级记录灌满）
        if (!availability.modelUsable()) {
            return AiOutcome.rule(degrade.get(), availability.unavailableReason(), null);
        }

        // 2) 限流
        if (!rateLimiter.tryAcquire(userId)) {
            return AiOutcome.rule(degrade.get(), "AI 请求过于频繁，已使用规则结果", null);
        }

        // 3) 每日配额（按成功+降级次数计，防止绕过限流慢速刷量）
        if (userId != null) {
            int used = callLogMapper.countTodayByUser(userId);
            int quota = settingsProvider.current().dailyQuota();
            if (used >= quota) {
                return AiOutcome.rule(degrade.get(),
                        "今日 AI 使用次数已达上限（" + quota + " 次）", null);
            }
        }

        // 4) 调用 + 解析校验（重试次数也读运行时配置）
        long start = System.currentTimeMillis();
        int attempts = Math.max(1, settingsProvider.current().maxAttempts());
        String lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ChatInvoker.ChatReply reply = chatInvoker.generate(systemPrompt + JSON_CONTRACT, userPrompt);
                String raw = reply.text();
                T value = parser.apply(raw);
                if (value == null) {
                    lastError = "模型输出无法解析为约定结构";
                    continue;
                }
                long cost = System.currentTimeMillis() - start;
                Long logId = recorder.record(scene, AiCallLog.STATUS_SUCCESS, null, null,
                        userPrompt, raw, cost, reply.promptTokens(), reply.completionTokens());
                return AiOutcome.ai(value, logId, cost);
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("AI 调用失败：scene={}, attempt={}/{}, cause={}",
                        scene, attempt, attempts, lastError);
            }
        }

        // 5) 全部尝试失败 —— 记失败流水后降级，业务仍然拿到可用结果
        long cost = System.currentTimeMillis() - start;
        T fallback = degrade.get();
        Long logId = recorder.record(scene, AiCallLog.STATUS_FAILED,
                "模型调用失败，已降级为规则结果", lastError,
                userPrompt, null, cost, null, null);
        return AiOutcome.rule(fallback, "模型暂时不可用，已使用规则结果", logId);
    }

    /** 供场景内部显式记一条降级流水（例如前置规则已判定风险，无需调用模型） */
    public void recordDegraded(String scene, String reason, String inputRaw) {
        recorder.recordAsync(scene, AiCallLog.STATUS_DEGRADED, reason, null,
                inputRaw, null, 0L, null, null);
    }
}
