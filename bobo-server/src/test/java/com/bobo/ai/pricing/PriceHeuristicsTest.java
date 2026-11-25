package com.bobo.ai.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定价规则的口径测试：成色归一、系数顺序、区间收敛。
 *
 * @author bobo
 */
class PriceHeuristicsTest {

    private final PriceHeuristics heuristics = new PriceHeuristics();

    @Test
    @DisplayName("成色写法归一：95 新 / 九五新 都落到 95新")
    void normalizeCondition() {
        assertThat(heuristics.normalizeCondition("95 新")).isEqualTo("95新");
        assertThat(heuristics.normalizeCondition("九五新")).isEqualTo("95新");
        assertThat(heuristics.normalizeCondition("九成新")).isEqualTo("9成新");
        assertThat(heuristics.normalizeCondition("全新")).isEqualTo("全新");
        assertThat(heuristics.normalizeCondition("伊拉克成色")).isNull();
    }

    @Test
    @DisplayName("成色系数随成色变旧单调下降，95新 为基准 1.0")
    void conditionFactorMonotonic() {
        assertThat(heuristics.conditionFactor("95新")).isEqualByComparingTo("1.0");
        assertThat(heuristics.conditionFactor("全新")).isGreaterThan(heuristics.conditionFactor("99新"));
        assertThat(heuristics.conditionFactor("99新")).isGreaterThan(heuristics.conditionFactor("95新"));
        assertThat(heuristics.conditionFactor("95新")).isGreaterThan(heuristics.conditionFactor("9成新"));
        assertThat(heuristics.conditionFactor("7成新及以下")).isLessThan(new BigDecimal("0.8"));
    }

    @Test
    @DisplayName("模型把 500 估成 9000 时，按同类中位价收敛到可信区间")
    void absurdRangeIsBounded() {
        PriceHeuristics.Bounds bounds = heuristics.bound(
                new BigDecimal("100"), new BigDecimal("9000"), new BigDecimal("1000"));

        assertThat(bounds.adjusted()).isTrue();
        assertThat(bounds.low()).isGreaterThanOrEqualTo(new BigDecimal("550"));
        assertThat(bounds.high()).isLessThanOrEqualTo(new BigDecimal("1800"));
        assertThat(heuristics.mid(bounds.low(), bounds.high())).isBetween(bounds.low(), bounds.high());
    }

    @Test
    @DisplayName("上下限写反会被纠正；过宽区间按低值 1.6 倍收口")
    void reversedAndTooWideRange() {
        PriceHeuristics.Bounds bounds = heuristics.bound(
                new BigDecimal("800"), new BigDecimal("200"), null);
        assertThat(bounds.low()).isLessThanOrEqualTo(bounds.high());

        PriceHeuristics.Bounds wide = heuristics.bound(
                new BigDecimal("100"), new BigDecimal("1000"), null);
        assertThat(wide.high()).isLessThanOrEqualTo(wide.low().multiply(new BigDecimal("1.6")));
    }
}
