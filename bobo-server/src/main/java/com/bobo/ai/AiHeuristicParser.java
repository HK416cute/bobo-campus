package com.bobo.ai;

import com.bobo.domain.entity.Category;
import com.bobo.domain.entity.Item;
import com.bobo.domain.vo.AiVos;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地启发式解析器：不依赖模型的"规则大脑"。
 *
 * <p><b>为什么单独成类</b>：它同时承担两个角色，且都必须可测：
 * <ol>
 *   <li><b>降级兜底</b>——没有 API key、模型超时或输出不可解析时，
 *       检索解析与发布助手仍然给出可用结果。直接决定"AI 挂掉会不会变成功能挂掉"；</li>
 *   <li><b>低成本前置</b>——能靠规则确定的（价格区间、分类、校区、成色、交易方式）
 *       不必问模型，减少 token 与延迟。</li>
 * </ol>
 * 抽出来之后它变成纯函数式的组件（输入是文本与候选集，输出是 VO），
 * 于是可以用普通单元测试覆盖"九成新以上""1000-2000""能当面交易"这些真实表达。
 * 这正是 AI 项目最容易缺失的一环：降级路径没人测，线上才发现降级后结果是错的。
 *
 * <p>坦率说明局限：规则靠关键词与正则，<b>语义泛化能力弱</b>
 * （"便宜点的代步工具"这类表达它抓不到），因此它只做兜底，不做主力。
 *
 * @author bobo
 */
@Component
public class AiHeuristicParser {

    /** "不超过500" / "500以内" / "500元以下" */
    private static final Pattern PRICE_BELOW = Pattern.compile(
            "(?:不超过|最多|低于|少于|预算)\\s*(\\d+(?:\\.\\d+)?)"
                    + "|(\\d+(?:\\.\\d+)?)\\s*(?:元)?\\s*(?:以内|以下|内)");
    /** "1000-2000" / "1000到2000" / "1000~2000" */
    private static final Pattern PRICE_RANGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:-|~|到|至)\\s*(\\d+(?:\\.\\d+)?)");
    /** "超过500" / "500以上" */
    private static final Pattern PRICE_ABOVE = Pattern.compile(
            "(?:超过|高于|大于|至少)\\s*(\\d+(?:\\.\\d+)?)"
                    + "|(\\d+(?:\\.\\d+)?)\\s*(?:元)?\\s*(?:以上|往上)");

    private static final List<String> CONDITION_LEVELS =
            List.of("全新", "99新", "95新", "9成新", "8成新", "7成新及以下");

    private static final List<String> SORT_VALUES = List.of("new", "priceAsc", "priceDesc", "want", "hot");

    // ------------------------------------------------------------------ 检索解析兜底

    /**
     * 规则解析检索词。
     *
     * @param query      用户输入
     * @param categories 候选分类（真实库数据，规则只在候选里选）
     * @param campuses   候选校区
     */
    public AiVos.SearchSlotsVO parseSearchQuery(String query, List<Category> categories,
                                                List<String> campuses) {
        AiVos.SearchSlotsVO vo = new AiVos.SearchSlotsVO();
        String text = query == null ? "" : query.trim();
        // 去掉空格后再匹配，"500 以内"与"500以内"要等价
        String normalized = text.replace(" ", "");

        applyPrice(normalized, vo);
        applyCategoryAndCampus(normalized, categories, campuses, vo);
        vo.setConditionLevel(inferConditionLevel(normalized));
        vo.setTradeType(inferTradeType(normalized));
        vo.setSortBy(inferSort(normalized));

        String keyword = stripStructuredParts(normalized);
        vo.setKeyword(StringUtils.hasText(keyword) ? keyword : text);
        vo.setExplanation("规则解析（未启用模型）：按价格区间与分类/校区关键词匹配");
        return vo;
    }

    /** 价格：区间 > 上限 > 下限，只认第一个匹配 */
    private void applyPrice(String normalized, AiVos.SearchSlotsVO vo) {
        Matcher range = PRICE_RANGE.matcher(normalized);
        if (range.find()) {
            BigDecimal low = new BigDecimal(range.group(1));
            BigDecimal high = new BigDecimal(range.group(2));
            // 写反了就纠正，模型与用户都可能写反
            vo.setMinPrice(low.min(high));
            vo.setMaxPrice(low.max(high));
            return;
        }
        Matcher below = PRICE_BELOW.matcher(normalized);
        if (below.find()) {
            String value = below.group(1) != null ? below.group(1) : below.group(2);
            vo.setMaxPrice(new BigDecimal(value));
            return;
        }
        Matcher above = PRICE_ABOVE.matcher(normalized);
        if (above.find()) {
            String value = above.group(1) != null ? above.group(1) : above.group(2);
            vo.setMinPrice(new BigDecimal(value));
        }
    }

    private void applyCategoryAndCampus(String normalized, List<Category> categories,
                                       List<String> campuses, AiVos.SearchSlotsVO vo) {
        for (Category c : categories) {
            if (StringUtils.hasText(c.getName()) && normalized.contains(c.getName())) {
                vo.setCategoryId(c.getCategoryId());
                vo.setCategoryName(c.getName());
                break;
            }
        }
        if (campuses != null) {
            for (String campus : campuses) {
                if (StringUtils.hasText(campus) && normalized.contains(campus)) {
                    vo.setCampus(campus);
                    break;
                }
            }
        }
    }

    /** 剔除已结构化的片段（价格、修饰词），剩下的才是文本匹配关键词 */
    private String stripStructuredParts(String normalized) {
        return normalized
                .replaceAll("(不超过|最多|低于|少于|预算|超过|高于|大于|至少)", "")
                .replaceAll("\\d+(?:\\.\\d+)?\\s*(?:元)?\\s*(?:以内|以下|内|以上|往上)", "")
                .replaceAll("\\d+(?:\\.\\d+)?\\s*(?:-|~|到|至)\\s*\\d+(?:\\.\\d+)?\\s*(?:元)?", "")
                .replaceAll("(能|可以|支持|想要|求|找|买|的|二手|闲置)", "");
    }

    // ------------------------------------------------------------------ 发布助手兜底

    /**
     * 规则生成发布草稿：标题取首行，分类/成色/标签靠关键词。
     *
     * @param text       卖家输入
     * @param categories 候选分类
     * @param maxTitleLen 标题最大长度（与库内字段对齐，由调用方传入）
     */
    public AiVos.ItemDraftVO draftFromText(String text, List<Category> categories, int maxTitleLen,
                                           int maxDescriptionLen) {
        AiVos.ItemDraftVO vo = new AiVos.ItemDraftVO();
        String trimmed = text == null ? "" : text.trim();
        String firstLine = trimmed.lines().findFirst().orElse(trimmed);
        vo.setTitle(clip(firstLine, maxTitleLen));
        vo.setDescription(clip(trimmed, maxDescriptionLen));
        vo.setConditionLevel(inferConditionLevel(trimmed.replace(" ", "")));
        if (vo.getConditionLevel() == null) {
            // 发布场景必须有成色，库内有默认值 95新
            vo.setConditionLevel("95新");
        }

        String normalized = trimmed.replace(" ", "");
        if (categories != null) {
            for (Category c : categories) {
                if (StringUtils.hasText(c.getName()) && normalized.contains(c.getName())) {
                    vo.setCategoryId(c.getCategoryId());
                    vo.setCategoryName(c.getName());
                    break;
                }
            }
        }

        List<String> tags = new ArrayList<>();
        if (containsAny(normalized, "小刀", "议价", "便宜", "可谈")) {
            tags.add("可小刀");
        }
        if (containsAny(normalized, "当面", "自提", "面交")) {
            tags.add("当面交易");
        }
        if (containsAny(normalized, "邮寄", "快递", "包邮")) {
            tags.add("可邮寄");
        }
        if (containsAny(normalized, "原装", "配件", "包装", "发票", "保修")) {
            tags.add("含配件");
        }
        if (containsAny(normalized, "全新", "未拆", "未使用")) {
            tags.add("全新未拆");
        }
        vo.setTags(tags);
        // 风险标记由调用方用 AiKeywordRules 补齐，保证规则口径只有一处
        vo.setRiskFlags(new ArrayList<>());
        return vo;
    }

    // ------------------------------------------------------------------ 公共推断

    /**
     * 从文本推断成色；推断不出返回 null（调用方决定默认值）。
     *
     * <p><b>匹配顺序必须"最具体优先"</b>，否则会被子串坑到：
     * {@code "95新"} 包含 {@code "5成新"}、{@code "几乎全新"} 包含 {@code "全新"}，
     * 按"从新到旧"的自然顺序写就会把 95新 判成"七成新及以下"、把几乎全新判成全新。
     * 这类错误在手动试几个样例时很难发现，所以顺序与测试都写死在这里。
     */
    public String inferConditionLevel(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        // 1) 带"几成新"字样的档位，从高到低
        if (containsAny(normalized, "九成新", "9成新")) {
            return "95新";
        }
        if (containsAny(normalized, "八成新", "8成新")) {
            return "8成新";
        }
        if (containsAny(normalized, "七成新", "7成新", "六成新", "6成新", "五成新")) {
            return "7成新及以下";
        }
        // 2) 纯数字档位：必须整词匹配，"95新" 不能被 "5成新" 这一类规则误伤
        if (containsAny(normalized, "99新", "几乎全新")) {
            return "99新";
        }
        if (normalized.contains("95新")) {
            return "95新";
        }
        if (normalized.contains("85新")) {
            return "8成新";
        }
        // 3) 描述性说法
        if (containsAny(normalized, "未拆", "未使用", "仅拆封", "没拆")) {
            return "全新";
        }
        // "全新" 放最后：它是最短的词，必须等更具体的形式都判断完
        if (normalized.contains("全新")) {
            return "全新";
        }
        return null;
    }

    /** 从文本推断交易方式；推断不出返回 null */
    public Integer inferTradeType(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        boolean face = containsAny(normalized, "当面", "面交", "自提");
        boolean mail = containsAny(normalized, "邮寄", "快递", "包邮", "发货");
        if (face && !mail) {
            return Item.TRADE_FACE;
        }
        if (mail && !face) {
            return Item.TRADE_MAIL;
        }
        return null;
    }

    private String inferSort(String normalized) {
        if (containsAny(normalized, "便宜", "价格从低到高", "最便宜", "低价")) {
            return "priceAsc";
        }
        if (containsAny(normalized, "最贵", "价格从高到低")) {
            return "priceDesc";
        }
        if (containsAny(normalized, "最想要", "最多人想要", "热门")) {
            return "want";
        }
        if (containsAny(normalized, "最新", "最新发布")) {
            return "new";
        }
        return "new";
    }

    /** 校验成色是否属于库内取值 */
    public boolean isKnownCondition(String level) {
        return level != null && CONDITION_LEVELS.contains(level);
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
