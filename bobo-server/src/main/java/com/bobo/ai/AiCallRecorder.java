package com.bobo.ai;

import com.bobo.ai.spi.ChatInvoker;
import com.bobo.domain.entity.AiCallLog;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * AI 调用流水记录器：把每次模型交互（含降级/失败）落成 {@code bobo_ai_call_log}。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>只记摘要不记原文</b>：输入输出都做 SHA-256 取前 16 位。既能做幂等与缓存判定，
 *       又不会把用户发布的正文沉进日志表（隐私与容量双赢）。</li>
 *   <li><b>写入与业务解耦</b>：真正的写库在 {@link AiCallLogWriter}，异步入口也在那边
 *       （{@code @Async} 不能被同类方法自调用，否则是假异步）。</li>
 *   <li><b>成本可算</b>：按 {@code bobo.ai.pricing} 单价把 token 折算成金额落库，
 *       于是「AI 花了多少钱」是一条 SQL 而不是一次人工估算。</li>
 * </ol>
 *
 * <p><b>为什么有 {@code recordAs}</b>：升级后文本与多模态使用不同模型名
 * （deepseek-flash / deepseek-v4-pro，且视觉模型可单独配置），provider 与 model
 * 都不能再从 {@link ChatInvoker} 单例上推断——否则视觉调用会被记成文本模型的流水，
 * 成本与分场景统计直接失真。
 *
 * @author bobo
 */
@Component
@RequiredArgsConstructor
public class AiCallRecorder {

    private final AiCallLogWriter writer;
    private final AiSettingsProvider settingsProvider;
    private final ChatInvoker chatInvoker;

    /**
     * 同步落一条流水（DeepSeek 文本场景），返回 {@code logId}。
     *
     * <p>之所以同步：调用方（如建议反馈）常要回指「这是哪次模型输出」。
     * 本方法内部只有一次 insert，开销可控；纯统计场景请用 {@link #recordAsync}。
     */
    public Long record(String scene, String status, String degradeReason, String errorMsg,
                       String inputRaw, String outputRaw, long latencyMs,
                       Integer promptTokens, Integer completionTokens) {
        return recordAs(chatInvoker.name(), settingsProvider.current().model(), null,
                scene, status, degradeReason, errorMsg, inputRaw, outputRaw, latencyMs,
                promptTokens, completionTokens);
    }

    /**
     * 指定 provider / model / 单价的流水写入。视觉（DeepSeek 多模态）与多轮撮合场景使用。
     *
     * @param provider 实现名，如 deepseek-vision / spring-ai-deepseek
     * @param model    实际模型名，null 时回落当前 DeepSeek 配置
     * @param rate     计价，null 时回落 {@code bobo.ai.pricing}
     */
    public Long recordAs(String provider, String model, CostRate rate,
                         String scene, String status, String degradeReason, String errorMsg,
                         String inputRaw, String outputRaw, long latencyMs,
                         Integer promptTokens, Integer completionTokens) {
        AiCallLog entity = writer.build(scene, status, degradeReason, errorMsg,
                shortHash(inputRaw), shortHash(outputRaw), latencyMs,
                promptTokens, completionTokens, provider, AuthContext.userIdOrNull());
        fillRuntimeFields(entity, promptTokens, completionTokens, model, rate);
        return writer.insert(entity);
    }

    /** 异步落流水，用于调用方不需要 logId 的场景（例如限流/前置规则已判定而无需调模型） */
    public void recordAsync(String scene, String status, String degradeReason, String errorMsg,
                            String inputRaw, String outputRaw, long latencyMs,
                            Integer promptTokens, Integer completionTokens) {
        // userId 必须在原线程取：异步线程里 AuthContext 的 ThreadLocal 是空的
        Long userId = AuthContext.userIdOrNull();
        AiCallLog entity = writer.build(scene, status, degradeReason, errorMsg,
                shortHash(inputRaw), shortHash(outputRaw), latencyMs,
                promptTokens, completionTokens, chatInvoker.name(), userId);
        fillRuntimeFields(entity, promptTokens, completionTokens,
                settingsProvider.current().model(), null);
        writer.insertAsync(entity);
    }

    /**
     * 补齐依赖运行时配置的两个字段：模型名与估算成本。
     * 放在这里而不是 {@code AiCallLogWriter}，是为了避免 Writer ←→ Recorder 的构造期循环依赖。
     */
    private void fillRuntimeFields(AiCallLog entity, Integer promptTokens, Integer completionTokens,
                                   String model, CostRate rate) {
        entity.setModel(model == null ? settingsProvider.current().model() : model);
        entity.setEstimatedCost(estimateCost(promptTokens, completionTokens, rate));
    }

    /**
     * 按**当前生效单价**折算金额（元），保留 6 位小数以免小额被舍成 0。
     *
     * <p>单价现在可以在后台配置（{@code bobo_ai_setting.price_input/price_output}），
     * 因此这里读运行时快照而不是构造期注入的 yml 值——否则管理员改完单价，
     * 成本统计仍按旧价计算，报表与"实际账单"对不上。
     */
    public BigDecimal estimateCost(Integer promptTokens, Integer completionTokens) {
        return estimateCost(promptTokens, completionTokens, null);
    }

    private BigDecimal estimateCost(Integer promptTokens, Integer completionTokens, CostRate rate) {
        BigDecimal inputPrice;
        BigDecimal outputPrice;
        if (rate == null) {
            AiSettingsProvider.RuntimeSettings settings = settingsProvider.current();
            inputPrice = settings.priceInput();
            outputPrice = settings.priceOutput();
        } else {
            inputPrice = rate.inputPerMillion();
            outputPrice = rate.outputPerMillion();
        }
        BigDecimal in = BigDecimal.valueOf(promptTokens == null ? 0 : promptTokens).multiply(inputPrice);
        BigDecimal out = BigDecimal.valueOf(completionTokens == null ? 0 : completionTokens).multiply(outputPrice);
        return in.add(out).divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    /** 内容摘要：SHA-256 前 16 位十六进制，够区分、不泄原文 */
    public static String shortHash(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然带 SHA-256，这里只是防御式返回，不抛给业务
            return null;
        }
    }

    /**
     * 计价（元 / 百万 token）。null 表示沿用管理后台配置的 DeepSeek 单价。
     */
    public record CostRate(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    }
}
