package com.bobo.ai;

import com.bobo.domain.entity.AiCallLog;
import com.bobo.domain.entity.Category;
import com.bobo.domain.entity.Item;
import com.bobo.domain.vo.AiVos;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 自然语言检索：把"500 以内能当面交易的山地车"这类话解析成结构化检索槽位。
 *
 * <p><b>为什么不做向量检索</b>：DeepSeek 不提供 embedding 接口，硬上需再引一家厂商与
 * Redis Stack（RediSearch），而本场景候选集只有几百到几千条，SQL 过滤完全够用。
 * 所以这里的实现是「<b>LLM 解析意图 → 喂现有 MyBatis-Plus 查询 → LLM 重排</b>」，
 * 文档与简历都按这个措辞写，不冒充 RAG。
 *
 * <p>这个方案在本场景其实<b>优于</b>朴素向量召回，原因有三：
 * <ol>
 *   <li><b>结构化条件本来就是硬约束</b>：价格区间、校区、交易方式是 SQL 的强项，
 *       向量相似度对这些离散条件几乎无感（"500 以内"检索成"500 左右"就错了）；</li>
 *   <li><b>零冷启动</b>：新发布的商品立刻可被检索到，不需要等向量化；</li>
 *   <li><b>可解释</b>：解析出的槽位直接回显给用户，他能看懂平台"理解成了什么"，
 *       并且可以手动改——这比"搜出来一堆不相关的"体验好得多。</li>
 * </ol>
 *
 * <p>代价也要说清楚：语义泛化弱于真向量检索（同义词靠模型解析而非向量距离兜），
 * 且每次检索多一跳模型调用——因此解析结果按 query 文本做了轻量缓存（见 {@link #parseCache}）。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSearchService {

    /** 解析结果缓存上限：检索是高频操作，同一句话不必重复问模型 */
    private static final int PARSE_CACHE_MAX = 500;

    private static final Map<String, AiVos.SearchSlotsVO> parseCache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AiVos.SearchSlotsVO> eldest) {
                    return size() > PARSE_CACHE_MAX;
                }
            });

    private static final String SYSTEM_PROMPT = """
            你是校园二手交易平台的搜索意图解析器。把用户的一句自然语言搜索词
            翻译成结构化检索条件，供平台做数据库查询。

            字段含义与取值约束：
            - keyword：仍需做文本匹配的核心词（如"山地车""iPad"）。去掉价格、成色、校区等已结构化的部分
            - categoryId：分类ID，只能从下面给出的候选分类里选；无法判断给 null
            - priceMin / priceMax：数字（元）。"500以内"→ priceMax=500；"1000到2000"→ 两个都给；
              "500以上"→ priceMin=500。没提到就给 null
            - conditionLevel：只能从 全新/99新/95新/9成新/8成新/7成新及以下 中选；"九成新以上"→ 95新
            - campus：校区，只能从候选校区里选；没提到给 null
            - tradeType：0 都支持 / 1 仅当面交易 / 2 仅邮寄。"能当面交易"→1，"可以邮寄"→2
            - sortBy：new 最新 / priceAsc 价格升 / priceDesc 价格降 / want 最想要 / hot 最多浏览；默认 new
            - explanation：一句话说明你的理解，会展示给用户看
            """;

    private final AiExecutor aiExecutor;
    private final SafeJsonParser jsonParser;
    private final AiItemContextService contextService;
    private final AiHeuristicParser heuristicParser;

    /**
     * 解析自然语言检索词。
     * 结果会回显到搜索栏且用户可改，因此**即使模型理解错了也不会造成"搜不到东西还找不到原因"**。
     */
    public AiVos.SearchSlotsVO parse(String query) {
        String key = query == null ? "" : query.trim();
        AiVos.SearchSlotsVO cached = parseCache.get(key);
        if (cached != null) {
            return cached;
        }

        List<Category> categories = contextService.enabledCategories();
        Map<Long, String> categoryNames = categories.stream()
                .collect(Collectors.toMap(Category::getCategoryId, Category::getName,
                        (a, b) -> a, LinkedHashMap::new));
        List<String> campuses = contextService.activeCampuses();

        AiOutcome<AiVos.SearchSlotsVO> outcome = aiExecutor.execute(
                AiCallLog.SCENE_SEARCH_PARSE,
                SYSTEM_PROMPT,
                buildPrompt(key, categories, campuses),
                raw -> parseSlots(raw, categoryNames, campuses),
                () -> heuristicParser.parseSearchQuery(key, categories, campuses));

        AiVos.SearchSlotsVO slots = outcome.value();
        slots.setSource(outcome.source());
        slots.setDegradeNote(outcome.degradeNote());
        slots.setLogId(outcome.logId());
        slots.setLatencyMs(outcome.latencyMs());
        // 只有模型结果才缓存：规则结果依赖当前库内分类/校区，库一变就该重算
        if (!outcome.degraded()) {
            parseCache.put(key, slots);
        }
        return slots;
    }

    /** 清空解析缓存（分类或校区变更后调用，避免缓存里的脏 ID 一直生效） */
    public void clearCache() {
        parseCache.clear();
    }

    // ------------------------------------------------------------------ 提示词

    private String buildPrompt(String query, List<Category> categories, List<String> campuses) {
        StringBuilder sb = new StringBuilder();
        sb.append("候选分类（categoryId=分类名）：\n");
        if (categories.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (Category c : categories) {
                sb.append(c.getCategoryId()).append('=').append(c.getName()).append('\n');
            }
        }
        sb.append("\n候选校区：");
        sb.append(campuses.isEmpty() ? "（无）" : String.join("、", campuses));
        AiItemContextService.PriceStats stats = contextService.overallPriceStats();
        if (stats != null) {
            sb.append("\n平台价位印象：").append(stats.basis())
                    .append("，中位价 ").append(stats.mid()).append(" 元");
        }
        sb.append("\n\n用户搜索词：").append(query);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 解析与校验

    private AiVos.SearchSlotsVO parseSlots(String raw, Map<Long, String> categoryNames,
                                           List<String> campuses) {
        return jsonParser.parse(raw, node -> {
            AiVos.SearchSlotsVO vo = new AiVos.SearchSlotsVO();
            String keyword = text(node, "keyword");
            Long categoryId = number(node, "categoryId");
            BigDecimal minPrice = decimal(node, "priceMin");
            BigDecimal maxPrice = decimal(node, "priceMax");
            String campus = text(node, "campus");

            // 全部字段都空说明解析失败，交给降级（避免返回一个"什么都不筛"的空槽位）
            boolean empty = !StringUtils.hasText(keyword)
                    && (categoryId == null || !categoryNames.containsKey(categoryId))
                    && minPrice == null && maxPrice == null
                    && !StringUtils.hasText(campus);
            if (empty) {
                return null;
            }

            vo.setKeyword(StringUtils.hasText(keyword) ? keyword.trim() : null);
            if (categoryId != null && categoryNames.containsKey(categoryId)) {
                vo.setCategoryId(categoryId);
                vo.setCategoryName(categoryNames.get(categoryId));
            }
            // 价格区间做一次纠正：模型偶尔会把上下限写反
            if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
                BigDecimal tmp = minPrice;
                minPrice = maxPrice;
                maxPrice = tmp;
            }
            vo.setMinPrice(minPrice);
            vo.setMaxPrice(maxPrice);
            vo.setConditionLevel(normalizeCondition(text(node, "conditionLevel")));
            if (StringUtils.hasText(campus)) {
                for (String candidate : campuses) {
                    if (candidate.equals(campus.trim())) {
                        vo.setCampus(candidate);
                        break;
                    }
                }
            }
            vo.setTradeType(normalizeTradeType(number(node, "tradeType")));
            vo.setSortBy(normalizeSort(text(node, "sortBy")));
            vo.setExplanation(clip(text(node, "explanation"), 120));
            return vo;
        });
    }

    /**
     * 规则兜底解析已抽到 {@link AiHeuristicParser}（可独立单测）。
     * 这里保留调用点，是为了让"降级实现"在 {@link AiExecutor#execute} 的参数里一眼可见。
     */

    // ------------------------------------------------------------------ 小工具

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

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            BigDecimal result = new BigDecimal(value.asText().trim());
            // 防御：模型偶尔会给出 0 或负数，视为未提供
            return result.compareTo(BigDecimal.ZERO) <= 0 ? null : result;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeCondition(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        for (String level : List.of("全新", "99新", "95新", "9成新", "8成新", "7成新及以下")) {
            if (level.equals(trimmed)) {
                return level;
            }
        }
        return null;
    }

    private String inferCondition(String text) {
        if (text.contains("全新") || text.contains("未拆")) {
            return "全新";
        }
        if (text.contains("九成新") || text.contains("9成新") || text.contains("95新")) {
            return "95新";
        }
        if (text.contains("八成新") || text.contains("8成新")) {
            return "8成新";
        }
        return null;
    }

    private Integer normalizeTradeType(Long raw) {
        if (raw == null) {
            return null;
        }
        int value = raw.intValue();
        return (value == Item.TRADE_BOTH || value == Item.TRADE_FACE || value == Item.TRADE_MAIL)
                ? value : null;
    }

    private String normalizeSort(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "new";
        }
        String trimmed = raw.trim();
        return List.of("new", "priceAsc", "priceDesc", "want", "hot").contains(trimmed) ? trimmed : "new";
    }

    private String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
