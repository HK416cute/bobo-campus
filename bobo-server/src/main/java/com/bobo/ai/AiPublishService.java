package com.bobo.ai;

import com.bobo.domain.entity.AiCallLog;
import com.bobo.domain.entity.Category;
import com.bobo.domain.vo.AiVos;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 发布助手：把卖家的一句话/草稿补全成结构化建议，回填发布表单。
 *
 * <p>分工是刻意的，也是这一块最值得讲的设计：
 * <ul>
 *   <li><b>模型负责语言</b>：拟标题、归成色、打标签、润色描述；</li>
 *   <li><b>数据库负责数字</b>：价格建议来自库内同类在售商品的真实分位（见
 *       {@link AiItemContextService}），模型不参与出价——大模型"估算价格"听起来很合理，
 *       但价格是要给用户当锚点的，猜错比不给更糟；</li>
 *   <li><b>规则负责兜底</b>：无 key、超时、输出不可解析时给出仍可用的草稿，
 *       发布流程不会因为 AI 抖动而中断。</li>
 * </ul>
 *
 * <p>分类是"约束解码"的落地：把真实分类清单塞进提示词，模型只能在候选里选；
 * 解析后再用 {@code validCategoryIds} 校验一遍，双保险。
 * 这一步把"模型返回一个不存在的分类ID"这类脏数据从源头消掉。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPublishService {

    /** 商品标题在库里是 VARCHAR(100)，这里留出余量 */
    private static final int MAX_TITLE_LEN = 60;
    private static final int MAX_DESCRIPTION_LEN = 500;
    private static final int MAX_TAG_COUNT = 6;
    private static final int MAX_TAG_LEN = 8;

    private static final String SYSTEM_PROMPT = """
            你是校园二手交易平台的商品信息助手。卖家会给你一段随手写的描述，
            你要把它整理成规范的商品信息，帮他更快通过审核并卖出去。

            必须输出的字段（一个都不能漏，缺信息用 null，但 categoryId 与 conditionLevel 不许为 null）：
            - title：20 字以内的商品标题，突出品牌、型号、成色、关键卖点；不要加书名号或引号
            - description：一段通顺的描述，补充成色细节、使用时长、附件、交易方式等；不要编造卖家没说过的信息
            - categoryId：商品所属分类的**数字ID**，只能取自下面给出的候选分类。
              只要描述能看出是什么东西，就必须选一个最合适的分类；
              例如 iPad/手机/键盘/显示器 → 数码，教材/考研书 → 图书，自行车/球拍 → 运动，
              冰箱/台灯/收纳 → 生活，衣服/鞋子 → 服饰。确实判断不了才给 null。
            - conditionLevel：只能从 全新/99新/95新/9成新/8成新/7成新及以下 中选一个；卖家没提就选 95新
            - tags：2 到 6 个短标签，每个不超过 6 个字，如 可小刀、当面交易、含配件
            - riskFlags：如果描述涉及违禁物品、学术作弊或不当内容，填入简短原因；否则给空数组

            注意：不要给出价格，价格由系统根据平台真实成交数据计算。
            """;

    private final AiExecutor aiExecutor;
    private final SafeJsonParser jsonParser;
    private final AiKeywordRules rules;
    private final AiItemContextService contextService;
    private final AiHeuristicParser heuristicParser;

    /**
     * 生成发布草稿建议。
     *
     * @param text       卖家输入的草稿文本
     * @param categoryId 卖家已选分类，可为 null
     * @return 结构化建议，{@code source} 标明来自模型还是规则
     */
    public AiVos.ItemDraftVO generateDraft(String text, Long categoryId) {
        List<Category> categories = contextService.enabledCategories();
        Map<Long, String> categoryNames = categories.stream()
                .collect(Collectors.toMap(Category::getCategoryId, Category::getName,
                        (a, b) -> a, LinkedHashMap::new));

        // 卖家已选分类的价格锚点；模型可能改分类，所以解析后再按最终分类算一次
        AiItemContextService.PriceStats askedStats =
                contextService.priceStats(categoryId, categoryNames.get(categoryId));

        AiOutcome<AiVos.ItemDraftVO> outcome = aiExecutor.execute(
                AiCallLog.SCENE_PUBLISH_DRAFT,
                SYSTEM_PROMPT,
                buildPrompt(text, categories, askedStats),
                raw -> parseDraft(raw, categoryNames),
                () -> fallbackDraft(text, categories));

        AiVos.ItemDraftVO draft = outcome.value();
        // 价格与来源永远以执行器与数据库为准，不信模型返回的这两个字段
        AiItemContextService.PriceStats finalStats = outcome.degraded()
                ? askedStats
                : contextService.priceStats(draft.getCategoryId(), draft.getCategoryName());
        draft.setPriceSuggestion(toPriceVo(finalStats));
        draft.setSource(outcome.source());
        draft.setDegradeNote(outcome.degradeNote());
        draft.setLogId(outcome.logId());
        draft.setLatencyMs(outcome.latencyMs());

        // 规则风险提示兜底：模型给出的 riskFlags 可能与本地规则不一致，取并集
        List<String> mergedFlags = new ArrayList<>();
        if (draft.getRiskFlags() != null) {
            mergedFlags.addAll(draft.getRiskFlags());
        }
        for (String flag : rules.riskFlagsFor(text)) {
            if (!mergedFlags.contains(flag)) {
                mergedFlags.add(flag);
            }
        }
        draft.setRiskFlags(mergedFlags);
        return draft;
    }

    // ------------------------------------------------------------------ 提示词

    private String buildPrompt(String text, List<Category> categories,
                               AiItemContextService.PriceStats priceStats) {
        StringBuilder sb = new StringBuilder();
        sb.append("可选分类（categoryId=分类名）：\n");
        if (categories.isEmpty()) {
            sb.append("（当前平台没有可用分类，categoryId 返回 null）\n");
        } else {
            for (Category c : categories) {
                sb.append(c.getCategoryId()).append('=').append(c.getName()).append('\n');
            }
        }
        if (priceStats != null) {
            sb.append("\n参考价位（来自库内真实在售商品）：").append(priceStats.basis())
                    .append("，25分位 ").append(priceStats.low())
                    .append(" 元，中位数 ").append(priceStats.mid())
                    .append(" 元，75分位 ").append(priceStats.high()).append(" 元\n");
        }
        sb.append("\n卖家描述：\n").append(text);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 解析与校验

    /**
     * 解析模型输出并做业务校验。
     * 返回 {@code null} 表示结果不可用，由 {@link AiExecutor} 触发规则降级。
     */
    private AiVos.ItemDraftVO parseDraft(String raw, Map<Long, String> categoryNames) {
        return jsonParser.parse(raw, node -> {
            String title = text(node, "title");
            if (!StringUtils.hasText(title)) {
                // 没有标题等于没用，直接判为不可用
                return null;
            }
            AiVos.ItemDraftVO vo = new AiVos.ItemDraftVO();
            vo.setTitle(clip(title.trim(), MAX_TITLE_LEN));
            vo.setDescription(clip(text(node, "description"), MAX_DESCRIPTION_LEN));
            // 分类必须落在真实分类里，越界一律置空，避免把脏 ID 写进前端表单
            Long categoryId = number(node, "categoryId");
            if (categoryId != null && categoryNames.containsKey(categoryId)) {
                vo.setCategoryId(categoryId);
                vo.setCategoryName(categoryNames.get(categoryId));
            }
            vo.setConditionLevel(normalizeCondition(text(node, "conditionLevel")));
            vo.setTags(normalizeTags(node.get("tags")));
            vo.setRiskFlags(stringList(node.get("riskFlags"), 5));
            return vo;
        });
    }

    /**
     * 规则兜底：委托给 {@link AiHeuristicParser}（可独立单测），
     * 这里只补一件解析器不管的事——风险标记。
     * 风险判定口径必须只有一处（{@link AiKeywordRules}），否则模型与规则两套口径会打架。
     */
    private AiVos.ItemDraftVO fallbackDraft(String text, List<Category> categories) {
        AiVos.ItemDraftVO vo = heuristicParser.draftFromText(text, categories,
                MAX_TITLE_LEN, MAX_DESCRIPTION_LEN);
        vo.setRiskFlags(rules.riskFlagsFor(text));
        return vo;
    }

    // ------------------------------------------------------------------ 小工具

    private AiVos.PriceSuggestionVO toPriceVo(AiItemContextService.PriceStats stats) {
        if (stats == null) {
            // 样本不足时明确不给建议，而不是编一个数
            return null;
        }
        AiVos.PriceSuggestionVO vo = new AiVos.PriceSuggestionVO();
        vo.setLow(stats.low());
        vo.setMid(stats.mid());
        vo.setHigh(stats.high());
        vo.setSampleCount(stats.sampleCount());
        vo.setBasis(stats.basis());
        return vo;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> stringList(JsonNode node, int max) {
        List<String> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            if (item != null && !item.isNull() && StringUtils.hasText(item.asText())) {
                list.add(clip(item.asText().trim(), MAX_TAG_LEN * 4));
                if (list.size() >= max) {
                    break;
                }
            }
        }
        return list;
    }

    private List<String> normalizeTags(JsonNode node) {
        List<String> tags = new ArrayList<>();
        for (String tag : stringList(node, MAX_TAG_COUNT)) {
            String clipped = clip(tag, MAX_TAG_LEN);
            if (!tags.contains(clipped)) {
                tags.add(clipped);
            }
        }
        return tags;
    }

    /**
     * 成色必须在库内取值集合里，否则回落到默认值。
     * 校验口径与规则解析器共用 {@link AiHeuristicParser#isKnownCondition}，
     * 避免"模型路径允许的取值"与"规则路径允许的取值"两套标准。
     */
    private String normalizeCondition(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "95新";
        }
        String trimmed = raw.trim();
        return heuristicParser.isKnownCondition(trimmed) ? trimmed : "95新";
    }

    private String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
