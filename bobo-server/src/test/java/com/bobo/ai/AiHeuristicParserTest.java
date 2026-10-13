package com.bobo.ai;

import com.bobo.domain.entity.Category;
import com.bobo.domain.entity.Item;
import com.bobo.domain.vo.AiVos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则兜底解析测试。
 *
 * <p>为什么这组测试比"接模型"的测试更重要：降级路径平时不被触发，
 * 一旦线上模型挂了才会走到这里——如果它给出的是错的结果（比如把"500以内"解析成下限500），
 * 用户看到的是"AI 降级后搜索全乱"，而监控上只显示"降级率上升"，根因极难定位。
 * 所以这些真实表达必须被钉死。
 *
 * @author bobo
 */
class AiHeuristicParserTest {

    private final AiHeuristicParser parser = new AiHeuristicParser();

    private static Category category(long id, String name) {
        Category c = new Category();
        c.setCategoryId(id);
        c.setName(name);
        return c;
    }

    private static final List<Category> CATEGORIES = List.of(
            category(1L, "数码"), category(2L, "图书"), category(3L, "运动"), category(4L, "生活"));
    private static final List<String> CAMPUSES = List.of("东校区", "西校区");

    // ------------------------------------------------------------------ 价格

    @Test
    @DisplayName("价格上限：不超过 / 以内 / 元以下 三种说法都识别为 priceMax")
    void priceUpperBound() {
        for (String query : List.of("不超过500的平板", "500以内的平板", "500元以下的平板")) {
            AiVos.SearchSlotsVO vo = parser.parseSearchQuery(query, CATEGORIES, CAMPUSES);
            assertThat(vo.getMaxPrice()).as(query).isEqualByComparingTo(new BigDecimal("500"));
            assertThat(vo.getMinPrice()).as(query).isNull();
        }
    }

    @Test
    @DisplayName("价格区间：1000-2000 / 1000到2000 都得到上下限")
    void priceRange() {
        AiVos.SearchSlotsVO a = parser.parseSearchQuery("1000-2000的笔记本", CATEGORIES, CAMPUSES);
        assertThat(a.getMinPrice()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(a.getMaxPrice()).isEqualByComparingTo(new BigDecimal("2000"));

        AiVos.SearchSlotsVO b = parser.parseSearchQuery("1000到2000的笔记本", CATEGORIES, CAMPUSES);
        assertThat(b.getMinPrice()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(b.getMaxPrice()).isEqualByComparingTo(new BigDecimal("2000"));
    }

    @Test
    @DisplayName("价格下限：500以上 / 超过500")
    void priceLowerBound() {
        AiVos.SearchSlotsVO a = parser.parseSearchQuery("500以上的自行车", CATEGORIES, CAMPUSES);
        assertThat(a.getMinPrice()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(a.getMaxPrice()).isNull();

        AiVos.SearchSlotsVO b = parser.parseSearchQuery("超过500的自行车", CATEGORIES, CAMPUSES);
        assertThat(b.getMinPrice()).isEqualByComparingTo(new BigDecimal("500"));
    }

    @Test
    @DisplayName("价格范围写反时自动纠正上下限")
    void priceRangeReversed() {
        AiVos.SearchSlotsVO vo = parser.parseSearchQuery("2000-1000的笔记本", CATEGORIES, CAMPUSES);
        assertThat(vo.getMinPrice()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(vo.getMaxPrice()).isEqualByComparingTo(new BigDecimal("2000"));
    }

    // ------------------------------------------------------------------ 分类 / 校区 / 成色 / 交易方式

    @Test
    @DisplayName("分类与校区按候选集精确匹配，而不是瞎猜")
    void categoryAndCampus() {
        AiVos.SearchSlotsVO vo = parser.parseSearchQuery("东校区的数码产品", CATEGORIES, CAMPUSES);
        assertThat(vo.getCategoryId()).isEqualTo(1L);
        assertThat(vo.getCategoryName()).isEqualTo("数码");
        assertThat(vo.getCampus()).isEqualTo("东校区");
    }

    @Test
    @DisplayName("候选集里没有的校区不会被写进槽位")
    void unknownCampusIgnored() {
        AiVos.SearchSlotsVO vo = parser.parseSearchQuery("南校区的数码产品", CATEGORIES, CAMPUSES);
        assertThat(vo.getCampus()).isNull();
    }

    @Test
    @DisplayName("成色推断覆盖各档，且只给库内合法取值")
    void conditionLevels() {
        assertThat(parser.inferConditionLevel("全新未拆的耳机")).isEqualTo("全新");
        assertThat(parser.inferConditionLevel("几乎全新的耳机")).isEqualTo("99新");
        assertThat(parser.inferConditionLevel("九成新的耳机")).isEqualTo("95新");
        assertThat(parser.inferConditionLevel("8成新的耳机")).isEqualTo("8成新");
        assertThat(parser.inferConditionLevel("七成新的耳机")).isEqualTo("7成新及以下");
        assertThat(parser.inferConditionLevel("一个耳机")).isNull();
        // 所有推断结果都必须落在库内取值集合里
        for (String text : List.of("全新", "99新", "九成新", "8成新", "七成新")) {
            assertThat(parser.isKnownCondition(parser.inferConditionLevel(text))).isTrue();
        }
    }

    @Test
    @DisplayName("成色子串陷阱：95新 不能被 5成新 规则吃掉，几乎全新 不能判成全新")
    void conditionSubstringTraps() {
        // "95新" 含 "5成新"，若匹配顺序写成"从新到旧"就会判成七成新及以下
        assertThat(parser.inferConditionLevel("95新 的平板")).isEqualTo("95新");
        // "几乎全新" 含 "全新"，必须先判 99新
        assertThat(parser.inferConditionLevel("几乎全新，只用过两次")).isEqualTo("99新");
        // 同理 "99新" 不能被"全新"截胡（这里两者都在，99新 更具体）
        assertThat(parser.inferConditionLevel("99新（几乎全新）")).isEqualTo("99新");
    }

    @Test
    @DisplayName("交易方式：只提当面 → 1；只提邮寄 → 2；都提或都不提 → 不限")
    void tradeType() {
        assertThat(parser.inferTradeType("能当面交易的显示器")).isEqualTo(Item.TRADE_FACE);
        assertThat(parser.inferTradeType("可以邮寄的显示器")).isEqualTo(Item.TRADE_MAIL);
        assertThat(parser.inferTradeType("当面或邮寄都行")).isNull();
        assertThat(parser.inferTradeType("一个显示器")).isNull();
    }

    @Test
    @DisplayName("排序：想便宜 → priceAsc，想热门 → want")
    void sort() {
        assertThat(parser.parseSearchQuery("便宜点的显示器", CATEGORIES, CAMPUSES).getSortBy())
                .isEqualTo("priceAsc");
        assertThat(parser.parseSearchQuery("最多人想要的显示器", CATEGORIES, CAMPUSES).getSortBy())
                .isEqualTo("want");
        assertThat(parser.parseSearchQuery("显示器", CATEGORIES, CAMPUSES).getSortBy())
                .isEqualTo("new");
    }

    @Test
    @DisplayName("关键词会剔除已结构化的价格与修饰词，剩下的才是文本匹配词")
    void keywordStripping() {
        AiVos.SearchSlotsVO vo = parser.parseSearchQuery("想要500以内的二手山地车", CATEGORIES, CAMPUSES);
        assertThat(vo.getMaxPrice()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(vo.getKeyword()).isEqualTo("山地车");
    }

    @Test
    @DisplayName("规则解析结果标明来源为 rule，前端才能打「规则推算」标签")
    void sourceIsRule() {
        AiVos.SearchSlotsVO vo = parser.parseSearchQuery("500以内的平板", CATEGORIES, CAMPUSES);
        assertThat(vo.getExplanation()).contains("规则解析");
        assertThat(vo.getKeyword()).isNotBlank();
    }

    // ------------------------------------------------------------------ 发布助手兜底

    @Test
    @DisplayName("发布兜底：标题取首行、分类命中、成色与标签都给出")
    void draftFromText() {
        AiVos.ItemDraftVO vo = parser.draftFromText(
                "九成新 iPad Air4 64G\n宿舍自用，含原装充电器，可小刀，当面交易",
                CATEGORIES, 60, 500);

        assertThat(vo.getTitle()).isEqualTo("九成新 iPad Air4 64G");
        assertThat(vo.getConditionLevel()).isEqualTo("95新");
        assertThat(vo.getTags()).contains("可小刀", "当面交易", "含配件");
        assertThat(vo.getDescription()).contains("宿舍自用");
    }

    @Test
    @DisplayName("发布兜底：描述里没提成色时给库内默认值 95新（不能是 null，库有 NOT NULL 约束）")
    void draftDefaultCondition() {
        AiVos.ItemDraftVO vo = parser.draftFromText("出一个平板", CATEGORIES, 60, 500);
        assertThat(vo.getConditionLevel()).isEqualTo("95新");
    }

    @Test
    @DisplayName("发布兜底：标题超长时按库内字段长度截断")
    void draftTitleClipped() {
        String longText = "九成新".repeat(50);
        AiVos.ItemDraftVO vo = parser.draftFromText(longText, CATEGORIES, 60, 500);
        assertThat(vo.getTitle()).hasSize(60);
    }

    @Test
    @DisplayName("发布兜底：描述里的分类名会被识别，识别不到则留空（不瞎猜）")
    void draftCategory() {
        AiVos.ItemDraftVO hit = parser.draftFromText("卖一本图书 考研数学", CATEGORIES, 60, 500);
        assertThat(hit.getCategoryId()).isEqualTo(2L);

        AiVos.ItemDraftVO miss = parser.draftFromText("卖个不知道是啥的东西", CATEGORIES, 60, 500);
        assertThat(miss.getCategoryId()).isNull();
    }

    @Test
    @DisplayName("空输入不抛异常")
    void emptyInput() {
        AiVos.SearchSlotsVO slots = parser.parseSearchQuery(null, CATEGORIES, CAMPUSES);
        assertThat(slots.getSortBy()).isEqualTo("new");
        AiVos.ItemDraftVO draft = parser.draftFromText(null, CATEGORIES, 60, 500);
        assertThat(draft.getConditionLevel()).isEqualTo("95新");
    }
}
