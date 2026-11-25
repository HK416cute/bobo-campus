package com.bobo.ai.pricing;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 定价的规则部分：成色枚举、成色系数、价格区间收敛。
 *
 * <p>这些逻辑必须可单测，而且<b>模型与规则共用同一套口径</b>——
 * 否则会出现「模型说 95新、规则说 9成新」的自我矛盾，用户不知道该信谁。
 *
 * <p>成色系数是相对「95新」的折价：二手市场里成色每降一档大约折 8%~18%。
 * 系数只用于把「同类平均价」折算到当前商品成色，不替代真实成交数据。
 *
 * @author bobo
 */
@Component
public class PriceHeuristics {

    /** 成色枚举，与商品发布表单、检索筛选项完全一致 */
    public static final List<String> CONDITIONS =
            List.of("全新", "99新", "95新", "9成新", "8成新", "7成新及以下");

    /** 未提及成色时的默认档（与 bobo_item.condition_level 默认值一致） */
    public static final String DEFAULT_CONDITION = "95新";

    /** 把任意文本归一到合法成色；无法识别返回 null */
    public String normalizeCondition(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        for (String condition : CONDITIONS) {
            if (condition.equals(value)) {
                return condition;
            }
        }
        // 允许模型输出「95 新」「九五新」这类写法
        String compact = value.replace(" ", "");
        if (compact.contains("九五新") || compact.contains("9.5成新")) {
            return "95新";
        }
        if (compact.contains("九成新")) {
            return "9成新";
        }
        if (compact.contains("八成新")) {
            return "8成新";
        }
        if (compact.contains("七成新") || compact.contains("六成新")) {
            return "7成新及以下";
        }
        for (String condition : CONDITIONS) {
            if (compact.contains(condition)) {
                return condition;
            }
        }
        return null;
    }

    /** 成色相对 95新 的价格系数 */
    public BigDecimal conditionFactor(String condition) {
        return switch (condition == null ? DEFAULT_CONDITION : condition) {
            case "全新" -> new BigDecimal("1.10");
            case "99新" -> new BigDecimal("1.05");
            case "95新" -> BigDecimal.ONE;
            case "9成新" -> new BigDecimal("0.92");
            case "8成新" -> new BigDecimal("0.82");
            case "7成新及以下" -> new BigDecimal("0.70");
            default -> BigDecimal.ONE;
        };
    }

    /**
     * 把模型给出的区间收敛到「平台同类价位」的可信范围。
     *
     * <p>模型偶尔会把 500 元的平板估成 5000 元（把原价当现价），
     * 直接展示会严重误导卖家。这里用同类中位价的 0.55~1.8 倍做硬边界：
     * 区间越界时收敛，并标记 {@code adjusted=true}，前端可以提示「已按平台数据校正」。
     */
    public Bounds bound(BigDecimal low, BigDecimal high, BigDecimal referenceMid) {
        if (low == null || high == null) {
            return new Bounds(low, high, false);
        }
        if (low.compareTo(high) > 0) {
            BigDecimal tmp = low;
            low = high;
            high = tmp;
        }
        boolean adjusted = false;
        if (referenceMid != null && referenceMid.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal min = referenceMid.multiply(new BigDecimal("0.55"));
            BigDecimal max = referenceMid.multiply(new BigDecimal("1.80"));
            if (low.compareTo(min) < 0 || high.compareTo(max) > 0) {
                low = low.max(min);
                high = high.min(max);
                adjusted = true;
            }
        }
        low = low.max(new BigDecimal("1.00"));
        high = high.max(low);
        // 区间宽到离谱时按低值 1.6 倍收口，避免「10~9999 元」这种没有信息量的建议
        if (high.compareTo(low.multiply(new BigDecimal("1.6"))) > 0) {
            high = low.multiply(new BigDecimal("1.6"));
            adjusted = true;
        }
        return new Bounds(scale(low), scale(high), adjusted);
    }

    /** 保留两位小数（金额字段是 DECIMAL(10,2)） */
    public BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 中值：模型给区间，系统算中值，避免模型再单独编一个「建议价」 */
    public BigDecimal mid(BigDecimal low, BigDecimal high) {
        if (low == null || high == null) {
            return null;
        }
        return scale(low.add(high).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP));
    }

    /** 收敛结果 */
    public record Bounds(BigDecimal low, BigDecimal high, boolean adjusted) {
    }
}
