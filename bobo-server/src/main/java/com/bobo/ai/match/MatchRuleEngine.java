package com.bobo.ai.match;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 撮合规则引擎：没有模型、模型超时或输出不可解析时的「确定性谈判对手」。
 *
 * <p>它有三个用途，缺一不可：
 * <ol>
 *   <li><b>降级</b>：没有配置任何模型时，用户点「发起撮合」仍能走完流程、
 *       拿到一个可确认的建议价，而不是看到「AI 不可用」；</li>
 *   <li><b>测试</b>：多轮协商的边界（预算、拒绝、轮次）可以完全确定地验证；</li>
 *   <li><b>每一步的兜底</b>：模型某一轮返回垃圾时，只降级那一轮，不中断整场协商。</li>
 * </ol>
 *
 * <p>策略刻意简单、可解释：
 * <ul>
 *   <li>买家首轮出价 = min(预算上限, 标价 × 0.85)，且不低于预算下限与标价 60%；</li>
 *   <li>卖家接受线随轮次从标价 90% 逐步降到 86%（越谈越接近成交），
 *       低于标价 78% 直接拒绝；</li>
 *   <li>买家在预算内直接接受；最多上浮 5% 作为「咬牙价」，超出则还价或拒绝。</li>
 * </ul>
 *
 * <p>注意：这里产出的是<b>建议价</b>，不是成交价。双方都点确认后才会进入下单，
 * 与人工确认原则一致。
 *
 * @author bobo
 */
@Component
public class MatchRuleEngine {

    /** 卖家底价比例：低于标价的 78% 直接拒绝 */
    private static final BigDecimal SELLER_FLOOR_RATIO = new BigDecimal("0.78");
    /** 买家可上浮比例：预算上限之上最多再让 5% */
    private static final BigDecimal BUYER_STRETCH_RATIO = new BigDecimal("1.05");
    /** 卖家首轮接受线 */
    private static final BigDecimal SELLER_TARGET_RATIO = new BigDecimal("0.90");
    /** 每轮让步 2 个百分点 */
    private static final BigDecimal SELLER_ROUND_STEP = new BigDecimal("0.02");

    /**
     * 买家首轮出价。
     *
     * @param price     商品标价
     * @param budgetMin 预算下限，可为 null
     * @param budgetMax 预算上限，可为 null
     */
    public Move buyerOpen(BigDecimal price, BigDecimal budgetMin, BigDecimal budgetMax) {
        BigDecimal cap = budgetMax != null
                ? budgetMax.min(price.multiply(new BigDecimal("0.92")))
                : price.multiply(new BigDecimal("0.85"));
        BigDecimal floor = budgetMin != null
                ? budgetMin.max(price.multiply(new BigDecimal("0.60")))
                : price.multiply(new BigDecimal("0.60"));
        BigDecimal offer = cap.max(floor).min(price);
        offer = money(offer.max(BigDecimal.ONE));
        return new Move(Sender.BUYER, Action.OFFER, offer,
                "我是买家 Agent：这件商品我出价 ¥" + offer.toPlainString()
                        + "，如果今天能定下来，我可以尽快当面交易。");
    }

    /** 卖家对当前出价的响应；轮次越往后接受线越低 */
    public Move sellerReply(BigDecimal price, BigDecimal offer, int round) {
        BigDecimal safeOffer = offer == null ? BigDecimal.ONE : offer;
        BigDecimal target = price.multiply(SELLER_TARGET_RATIO
                .subtract(SELLER_ROUND_STEP.multiply(BigDecimal.valueOf(Math.max(0, round - 1)))));
        target = target.max(price.multiply(new BigDecimal("0.84")));

        if (safeOffer.compareTo(target) >= 0) {
            return new Move(Sender.SELLER, Action.ACCEPT, money(safeOffer),
                    "卖家 Agent：¥" + money(safeOffer).toPlainString() + " 可以，成交。");
        }
        if (safeOffer.compareTo(price.multiply(SELLER_FLOOR_RATIO)) < 0) {
            return new Move(Sender.SELLER, Action.REJECT, null,
                    "卖家 Agent：这个价格与我的预期差距较大，暂时不能接受。");
        }
        BigDecimal counter = safeOffer.multiply(new BigDecimal("1.03"));
        counter = counter.max(target).min(price.multiply(new BigDecimal("0.98")));
        counter = money(counter.max(safeOffer.add(new BigDecimal("0.01"))));
        return new Move(Sender.SELLER, Action.COUNTER, counter,
                "卖家 Agent：¥" + money(safeOffer).toPlainString()
                        + " 有点低，考虑到成色和配件，最低 ¥" + counter.toPlainString() + "。");
    }

    /** 买家对卖家还价的响应 */
    public Move buyerReply(BigDecimal price, BigDecimal counter, BigDecimal budgetMin,
                           BigDecimal budgetMax) {
        BigDecimal safeCounter = counter == null ? price : counter;
        BigDecimal cap = budgetMax != null ? budgetMax : price.multiply(new BigDecimal("0.92"));
        BigDecimal stretch = cap.multiply(BUYER_STRETCH_RATIO).min(price);

        if (safeCounter.compareTo(cap) <= 0) {
            return new Move(Sender.BUYER, Action.ACCEPT, money(safeCounter),
                    "买家 Agent：¥" + money(safeCounter).toPlainString() + " 可以接受，就按这个价格。");
        }
        if (safeCounter.compareTo(stretch) > 0) {
            return new Move(Sender.BUYER, Action.REJECT, null,
                    "买家 Agent：这已经超出我的预算了，暂时不考虑，谢谢。");
        }
        BigDecimal next = safeCounter.multiply(new BigDecimal("0.98")).min(stretch);
        if (budgetMin != null) {
            next = next.max(budgetMin);
        }
        next = money(next.max(BigDecimal.ONE));
        return new Move(Sender.BUYER, Action.COUNTER, next,
                "买家 Agent：我再让一步，¥" + next.toPlainString()
                        + "，这是我能接受的最高价了。");
    }

    /**
     * 完整模拟一场协商：供测试与「整场模型不可用」时快速给出结果。
     */
    public Simulation simulate(BigDecimal price, BigDecimal budgetMin, BigDecimal budgetMax,
                               int maxRounds) {
        List<Move> moves = new ArrayList<>();
        Move opening = buyerOpen(price, budgetMin, budgetMax);
        moves.add(opening);
        BigDecimal current = opening.price();
        int rounds = 0;

        for (int round = 1; round <= Math.max(1, maxRounds); round++) {
            Move seller = sellerReply(price, current, round);
            moves.add(seller);
            rounds = round;
            if (seller.action() == Action.ACCEPT) {
                return new Simulation(true, seller.price(), moves, rounds,
                        "经过 " + round + " 轮协商，卖家接受 ¥" + seller.price().toPlainString() + "。");
            }
            if (seller.action() == Action.REJECT) {
                return new Simulation(false, null, moves, rounds, "双方价格差距较大，未能达成一致。");
            }

            Move buyer = buyerReply(price, seller.price(), budgetMin, budgetMax);
            moves.add(buyer);
            if (buyer.action() == Action.ACCEPT) {
                return new Simulation(true, buyer.price(), moves, rounds,
                        "经过 " + round + " 轮协商，买家接受 ¥" + buyer.price().toPlainString() + "。");
            }
            if (buyer.action() == Action.REJECT) {
                return new Simulation(false, null, moves, rounds, "买家预算不足，未能达成一致。");
            }
            current = buyer.price();
        }
        return new Simulation(false, null, moves, rounds,
                "经过 " + rounds + " 轮协商仍未达成一致，建议站内私信继续沟通。");
    }

    public BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 协商角色 */
    public enum Sender {
        BUYER("buyer_agent"), SELLER("seller_agent"), SYSTEM("system");

        private final String value;

        Sender(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** 协商动作 */
    public enum Action {
        OFFER("offer"), COUNTER("counter"), ACCEPT("accept"), REJECT("reject");

        private final String value;

        Action(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Action of(String value) {
            for (Action action : values()) {
                if (action.value.equalsIgnoreCase(value)) {
                    return action;
                }
            }
            return null;
        }
    }

    /** 一步协商 */
    public record Move(Sender sender, Action action, BigDecimal price, String content) {
    }

    /** 规则模拟结果 */
    public record Simulation(boolean agreed, BigDecimal agreedPrice, List<Move> moves,
                             int rounds, String summary) {
    }
}
