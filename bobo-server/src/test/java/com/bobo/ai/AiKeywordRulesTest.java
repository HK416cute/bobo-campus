package com.bobo.ai;

import com.bobo.domain.entity.AiModeration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词规则测试。
 *
 * <p>规则是内容审核的第一道闸门，也是无模型时的唯一防线。
 * 它出错的两个方向都很贵：漏判 → 违规内容进站；误判 → 正常商品被拦、卖家体验崩。
 * 因此正常文案必须出现在测试里，不能只测"能不能抓到脏词"。
 *
 * @author bobo
 */
class AiKeywordRulesTest {

    private final AiKeywordRules rules = new AiKeywordRules();

    @Test
    @DisplayName("正常校园闲置文案不命中，风险为低")
    void normalContentIsClean() {
        List<String> normal = List.of(
                "九成新 iPad Air4 64G，宿舍自用，含原装充电器，可小刀，东校区当面交易",
                "考研数学 张宇1000题，全新未拆，包邮",
                "宿舍小冰箱，海尔 50L，自提优先，价格可谈",
                "机械键盘 87 键红轴，用了半年，功能正常");
        for (String content : normal) {
            AiKeywordRules.RuleResult result = rules.evaluate(content);
            assertThat(result.matched()).as(content).isFalse();
            assertThat(result.riskLevel()).as(content).isEqualTo(AiModeration.RISK_LOW);
            assertThat(rules.riskFlagsFor(content)).as(content).isEmpty();
        }
    }

    @Test
    @DisplayName("违禁物品命中即高风险，并建议拦截")
    void illegalItemsAreHighRisk() {
        AiKeywordRules.RuleResult result = rules.evaluate("出售管制刀具，价格好商量");
        assertThat(result.matched()).isTrue();
        assertThat(result.riskLevel()).isEqualTo(AiModeration.RISK_HIGH);
        assertThat(result.categories()).contains(AiKeywordRules.CATEGORY_ILLEGAL);
        assertThat(result.reason()).contains("管制刀具");

        var vo = result.toVo("item", 1L, "命中规则");
        assertThat(vo.getAutoReject()).isTrue();
        assertThat(vo.getSource()).isEqualTo(AiExecutor.SOURCE_RULE);
    }

    @Test
    @DisplayName("学术作弊是校园场景高发项，同样按高风险处理")
    void academicCheating() {
        AiKeywordRules.RuleResult result = rules.evaluate("专业代写论文，包过，价格私聊");
        assertThat(result.riskLevel()).isEqualTo(AiModeration.RISK_HIGH);
        assertThat(result.categories()).contains(AiKeywordRules.CATEGORY_ACADEMIC);
        assertThat(rules.riskFlagsFor("代写论文 包过")).contains(AiKeywordRules.CATEGORY_ACADEMIC);
    }

    @Test
    @DisplayName("欺诈信号为中风险：提示管理员重点看，但不自动拦截")
    void fraudIsMediumRisk() {
        AiKeywordRules.RuleResult result = rules.evaluate("要求先付款后发货，微信转账优先");
        assertThat(result.riskLevel()).isEqualTo(AiModeration.RISK_MEDIUM);
        assertThat(result.categories()).contains(AiKeywordRules.CATEGORY_FRAUD);
        assertThat(result.toVo("item", 1L, null).getAutoReject()).isFalse();
    }

    @Test
    @DisplayName("联系方式引流为低风险，不进入商品风险标记（太常见会淹没真问题）")
    void contactIsLowRiskAndNotFlagged() {
        AiKeywordRules.RuleResult result = rules.evaluate("想要的话加微信详聊");
        assertThat(result.riskLevel()).isEqualTo(AiModeration.RISK_LOW);
        assertThat(result.categories()).contains(AiKeywordRules.CATEGORY_CONTACT);
        // 发布助手的风险标记只取违禁/作弊/不当内容三类
        assertThat(rules.riskFlagsFor("加微信详聊")).isEmpty();
    }

    @Test
    @DisplayName("多条命中时取最高风险等级，理由列出全部命中词")
    void multipleHitsTakeHighestLevel() {
        AiKeywordRules.RuleResult result = rules.evaluate("代写论文，加微信详聊，先付款后发货");
        assertThat(result.riskLevel()).isEqualTo(AiModeration.RISK_HIGH);
        assertThat(result.categories()).contains(AiKeywordRules.CATEGORY_ACADEMIC,
                AiKeywordRules.CATEGORY_CONTACT, AiKeywordRules.CATEGORY_FRAUD);
        assertThat(result.reason()).contains("代写论文");
    }

    @Test
    @DisplayName("空内容与 null 不抛异常，按低风险处理")
    void emptyContent() {
        assertThat(rules.evaluate(null).matched()).isFalse();
        assertThat(rules.evaluate("").matched()).isFalse();
        assertThat(rules.evaluate("   ").matched()).isFalse();
        assertThat(rules.riskFlagsFor(null)).isEmpty();
    }

    @Test
    @DisplayName("大小写不敏感：带英文混排的文案也能命中")
    void caseInsensitive() {
        // 词表里没有英文词，但归一化用 toLowerCase 保证"加QQ"与"加qq"一致
        assertThat(rules.evaluate("加qq私聊").matched()).isTrue();
        assertThat(rules.evaluate("加QQ私聊").matched()).isTrue();
    }
}
