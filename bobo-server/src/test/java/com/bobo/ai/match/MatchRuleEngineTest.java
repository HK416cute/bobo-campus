package com.bobo.ai.match;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则协商引擎：无模型时撮合仍必须能跑完、且不越界。
 *
 * @author bobo
 */
class MatchRuleEngineTest {

    private final MatchRuleEngine engine = new MatchRuleEngine();

    @Test
    @DisplayName("预算充足时 3 轮内达成，成交价不超过预算上浮 5%")
    void reachesAgreementWithinBudget() {
        MatchRuleEngine.Simulation simulation = engine.simulate(
                new BigDecimal("1000"), new BigDecimal("800"), new BigDecimal("900"), 3);

        assertThat(simulation.agreed()).isTrue();
        assertThat(simulation.agreedPrice()).isNotNull();
        assertThat(simulation.agreedPrice())
                .isLessThanOrEqualTo(new BigDecimal("900").multiply(new BigDecimal("1.05")));
        assertThat(simulation.moves()).isNotEmpty();
        assertThat(simulation.rounds()).isBetween(1, 3);
    }

    @Test
    @DisplayName("预算远低于卖家底价时明确失败，而不是硬凑一个成交价")
    void rejectsWhenBudgetTooLow() {
        MatchRuleEngine.Simulation simulation = engine.simulate(
                new BigDecimal("1000"), new BigDecimal("300"), new BigDecimal("400"), 3);

        assertThat(simulation.agreed()).isFalse();
        assertThat(simulation.agreedPrice()).isNull();
        assertThat(simulation.summary()).contains("未能达成一致");
    }

    @Test
    @DisplayName("首轮出价不高于标价，也不低于预算下限")
    void openingOfferIsSane() {
        MatchRuleEngine.Move opening = engine.buyerOpen(
                new BigDecimal("1000"), new BigDecimal("700"), new BigDecimal("880"));

        assertThat(opening.action()).isEqualTo(MatchRuleEngine.Action.OFFER);
        assertThat(opening.price()).isBetween(new BigDecimal("700"), new BigDecimal("1000"));
    }

    @Test
    @DisplayName("卖家还价始终高于买家出价、且不超过标价")
    void sellerCounterIsValid() {
        MatchRuleEngine.Move counter = engine.sellerReply(
                new BigDecimal("1000"), new BigDecimal("800"), 1);

        assertThat(counter.action()).isIn(MatchRuleEngine.Action.COUNTER,
                MatchRuleEngine.Action.ACCEPT, MatchRuleEngine.Action.REJECT);
        if (counter.action() == MatchRuleEngine.Action.COUNTER) {
            assertThat(counter.price()).isGreaterThan(new BigDecimal("800"));
            assertThat(counter.price()).isLessThanOrEqualTo(new BigDecimal("1000"));
        }
    }
}
