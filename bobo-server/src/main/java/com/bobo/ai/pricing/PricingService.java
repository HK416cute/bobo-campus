package com.bobo.ai.pricing;

import com.bobo.ai.AiCallRecorder;
import com.bobo.ai.AiExecutor;
import com.bobo.ai.AiHeuristicParser;
import com.bobo.ai.AiItemContextService;
import com.bobo.ai.AiOutcome;
import com.bobo.ai.SafeJsonParser;
import com.bobo.ai.spi.VisionInvoker;
import com.bobo.ai.vision.ImagePreprocessor;
import com.bobo.common.exception.BizException;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.AiCallLog;
import com.bobo.domain.entity.Category;
import com.bobo.domain.entity.PriceEstimate;
import com.bobo.domain.vo.AiUpgradeVos;
import com.bobo.mapper.CategoryMapper;
import com.bobo.mapper.PriceEstimateMapper;
import com.bobo.security.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 模块二：智能定价与成色评估（DeepSeek 多模态 + 平台真实价位）。
 *
 * <p>设计原则与既有 AI 层保持一致：<b>模型负责「看图说话」，数据库负责「值多少钱」</b>。
 * 但定价场景与发布助手不同——成色必须看图片，纯文本模型无法胜任，
 * 因此这里用 DeepSeek 的原生多模态能力（deepseek-flash），并把平台同类在售分位、
 * 近 90 天成交分位一起作为上下文，让模型在真实价位附近给区间，而不是凭印象估价。
 *
 * <p>三道保险：
 * <ol>
 *   <li>视觉模型不可用 / 没有图片 / 调用失败 → 规则估价（价位分位 × 成色系数），
 *       并在 {@code source=rule} 与 {@code degradeNote} 里说清楚；</li>
 *   <li>模型区间越界 → 按平台同类中位价的 0.55~1.8 倍收敛，避免「500 元估成 5000」；</li>
 *   <li>卖家必须点「采用建议」并提交发布，才会把 AI 建议写入商品；系统不自动改价。</li>
 * </ol>
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingService {

    /** 成交样本回看天数 */
    private static final int SOLD_DAYS = 90;
    /** 单次估价的最大输出 token：够写 4 条理由与一个区间 */
    private static final int MAX_TOKENS = 900;
    private static final int MAX_REASONS = 5;

    private static final String SYSTEM_PROMPT = """
            你是校园二手商品评估助手，服务于 bobo 校园二手交易平台。
            卖家会给你商品图片、文字描述、卖家自述成色，以及平台同类商品的真实价位数据。
            你要判断商品成色，并给出建议价格区间。

            成色只能从以下枚举中选一个，不得自造：
            全新 / 99新 / 95新 / 9成新 / 8成新 / 7成新及以下

            定价规则：
            1. 必须参考下方「同类在售」与「近期成交」数据，不要脱离数据凭空给价；
            2. 图片能看出明显磨损、磕碰、缺配件时，成色与价格应更保守；
            3. 数据不足时给出更宽的区间，并把 confidence 调低；
            4. 价格单位是人民币元，priceLow 不得大于 priceHigh。

            输出字段：
            - conditionGrade：成色枚举之一；
            - priceLow / priceHigh：建议价格区间（数字，不带单位）；
            - confidence：0 到 1 的小数，表示你对本次判断的把握；
            - reasons：2 到 4 条简短理由，分别说明成色依据、价格依据、风险点；
            - riskFlags：可选，如「图片模糊」「疑似配件缺失」，没有就给空数组。
            """;

    private final AiExecutor aiExecutor;
    private final SafeJsonParser jsonParser;
    private final AiItemContextService contextService;
    private final AiHeuristicParser heuristicParser;
    private final PriceHeuristics heuristics;
    private final ImagePreprocessor imagePreprocessor;
    private final PriceEstimateMapper estimateMapper;
    private final CategoryMapper categoryMapper;
    private final VisionInvoker visionInvoker;
    private final ObjectMapper objectMapper;

    /**
     * 估价：发布前/编辑时都能调用。结果落库并返回，卖家决定是否采用。
     */
    public AiUpgradeVos.PriceEstimateVO estimate(Dtos.PriceEstimateDTO dto) {
        Long userId = AuthContext.userId();
        long start = System.currentTimeMillis();

        Category category = null;
        if (dto.categoryId() != null) {
            category = categoryMapper.selectById(dto.categoryId());
            if (category == null) {
                throw new BizException("分类不存在，请重新选择");
            }
        }
        String categoryName = category == null ? null : category.getName();
        String title = clip(dto.title(), 100);
        String description = clip(dto.description(), 500);
        String declared = heuristics.normalizeCondition(dto.conditionLevel());

        AiItemContextService.PriceStats onSale = contextService.priceStats(dto.categoryId(), categoryName);
        AiItemContextService.PriceStats sold = contextService.soldPriceStats(dto.categoryId(), categoryName, SOLD_DAYS);
        List<VisionInvoker.ImageAttachment> images = imagePreprocessor.prepare(dto.images());
        int imageCount = images.size();

        String prompt = buildPrompt(title, description, categoryName, declared,
                dto.askingPrice(), onSale, sold, imageCount);

        BigDecimal targetReference = referenceMid(sold, onSale, dto.askingPrice());
        AiOutcome<EstimateDraft> outcome = aiExecutor.executeVision(
                AiCallLog.SCENE_PRICE_ESTIMATE,
                SYSTEM_PROMPT,
                prompt,
                images,
                visionInvoker,
                MAX_TOKENS,
                null,
                raw -> parseModel(raw, declared, targetReference, onSale, sold),
                () -> ruleEstimate(title, description, declared, dto.askingPrice(), onSale, sold));

        EstimateDraft draft = outcome.value();
        PriceEstimate entity = persist(userId, dto, title, description, category,
                imageCount, draft, outcome);

        AiUpgradeVos.PriceEstimateVO vo = toVo(entity, outcome);
        vo.setLatencyMs(System.currentTimeMillis() - start);
        vo.setImageCount(imageCount);
        return vo;
    }

    // ------------------------------------------------------------------ 提示词

    private String buildPrompt(String title, String description, String categoryName,
                               String declared, BigDecimal askingPrice,
                               AiItemContextService.PriceStats onSale,
                               AiItemContextService.PriceStats sold,
                               int imageCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("商品标题：").append(StringUtils.hasText(title) ? title : "（未填）").append('\n');
        sb.append("商品描述：").append(StringUtils.hasText(description) ? description : "（未填）").append('\n');
        sb.append("分类：").append(categoryName == null ? "未选择" : categoryName).append('\n');
        sb.append("卖家自述成色：").append(declared == null ? "未说明" : declared).append('\n');
        if (askingPrice != null) {
            sb.append("卖家心理价：").append(askingPrice).append(" 元\n");
        }
        sb.append("送审图片数：").append(imageCount).append(" 张\n\n");

        sb.append("同类在售价位：");
        if (onSale == null) {
            sb.append("样本不足（少于 3 件），不宜作为强锚点\n");
        } else {
            sb.append(onSale.basis())
                    .append("；25 分位 ").append(onSale.low())
                    .append(" 元，中位 ").append(onSale.mid())
                    .append(" 元，75 分位 ").append(onSale.high()).append(" 元\n");
        }
        sb.append("近期成交价位：");
        if (sold == null) {
            sb.append("样本不足（少于 3 笔），可参考在售价但需保守\n");
        } else {
            sb.append(sold.basis())
                    .append("；25 分位 ").append(sold.low())
                    .append(" 元，中位 ").append(sold.mid())
                    .append(" 元，75 分位 ").append(sold.high()).append(" 元\n");
        }
        sb.append("\n请综合图片与以上数据给出成色与价格区间。");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 模型输出解析

    private EstimateDraft parseModel(String raw, String declared, BigDecimal referenceMid,
                                     AiItemContextService.PriceStats onSale,
                                     AiItemContextService.PriceStats sold) {
        return jsonParser.parse(raw, node -> {
            String condition = heuristics.normalizeCondition(text(node, "conditionGrade"));
            if (condition == null) {
                condition = declared;
            }
            if (condition == null) {
                return null;
            }
            BigDecimal low = number(node, "priceLow");
            BigDecimal high = number(node, "priceHigh");
            if (low == null || high == null) {
                return null;
            }
            PriceHeuristics.Bounds bounds = heuristics.bound(low, high, referenceMid);
            if (bounds.low() == null || bounds.high() == null) {
                return null;
            }
            List<String> reasons = stringList(node.get("reasons"));
            if (bounds.adjusted()) {
                reasons.add("已按平台同类价位校正建议区间");
            }
            List<String> riskFlags = stringList(node.get("riskFlags"));
            for (String flag : riskFlags) {
                reasons.add("风险提示：" + flag);
            }
            BigDecimal confidence = decimal(node, "confidence");
            if (confidence == null) {
                confidence = new BigDecimal("0.60");
            }
            confidence = confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE);
            return new EstimateDraft(condition, bounds.low(), bounds.high(), confidence,
                    trimReasons(reasons), basis(onSale, sold), sampleCount(onSale, sold),
                    bounds.adjusted());
        });
    }

    // ------------------------------------------------------------------ 规则兜底

    /**
     * 视觉不可用时的规则估价：以成交分位（优先）或在售分位为锚，乘成色系数。
     * 没有任何样本时明确返回「无法给出可靠区间」，而不是编一个数。
     */
    private EstimateDraft ruleEstimate(String title, String description, String declared,
                                       BigDecimal askingPrice,
                                       AiItemContextService.PriceStats onSale,
                                       AiItemContextService.PriceStats sold) {
        String condition = declared;
        if (condition == null) {
            condition = heuristics.normalizeCondition(
                    heuristicParser.inferConditionLevel((title + " " + description).replace(" ", "")));
        }
        if (condition == null) {
            condition = PriceHeuristics.DEFAULT_CONDITION;
        }

        List<String> reasons = new ArrayList<>();
        BigDecimal reference = null;
        String basis;
        int sampleCount = 0;
        BigDecimal confidence = BigDecimal.ZERO;

        if (sold != null) {
            reference = sold.mid();
            basis = sold.basis();
            sampleCount = sold.sampleCount();
            confidence = new BigDecimal("0.50");
            reasons.add("近 90 天同类成交中位价 " + sold.mid() + " 元，作为主要锚点");
        } else if (onSale != null) {
            reference = onSale.mid();
            basis = onSale.basis();
            sampleCount = onSale.sampleCount();
            confidence = new BigDecimal("0.35");
            reasons.add("同类在售中位价 " + onSale.mid() + " 元，作为参考锚点");
        } else {
            basis = "同类样本不足";
        }

        BigDecimal low = null;
        BigDecimal high = null;
        if (reference != null) {
            BigDecimal factor = heuristics.conditionFactor(condition);
            BigDecimal mid = reference.multiply(factor);
            low = heuristics.scale(mid.multiply(new BigDecimal("0.90")));
            high = heuristics.scale(mid.multiply(new BigDecimal("1.12")));
            PriceHeuristics.Bounds bounds = heuristics.bound(low, high, reference);
            low = bounds.low();
            high = bounds.high();
            reasons.add("成色「" + condition + "」相对 95新 的系数为 " + factor.stripTrailingZeros().toPlainString());
        } else if (askingPrice != null) {
            // 连同类样本都没有：只能围绕卖家心理价给一个提示性区间，置信度很低。
            // 仍然乘成色系数，避免“9成新”和“全新”给出同一个价。
            BigDecimal factor = heuristics.conditionFactor(condition);
            BigDecimal anchor = askingPrice.multiply(factor);
            low = heuristics.scale(anchor.multiply(new BigDecimal("0.85")));
            high = heuristics.scale(anchor.multiply(new BigDecimal("1.05")));
            confidence = new BigDecimal("0.20");
            basis = "无同类样本，仅参考卖家心理价";
            reasons.add("平台暂无同类成交/在售样本，以下区间仅围绕心理价按成色折算，参考价值有限");
        } else {
            reasons.add("平台暂无同类成交/在售样本，且未填写心理价，无法给出可靠区间");
        }

        if (reference != null) {
            reasons.add("未启用视觉模型或没有可用图片，未分析实物磨损");
        }
        return new EstimateDraft(condition, low, high, confidence, trimReasons(reasons),
                basis, sampleCount, false);
    }

    // ------------------------------------------------------------------ 落库与采用

    private PriceEstimate persist(Long userId, Dtos.PriceEstimateDTO dto,
                                  String title, String description, Category category,
                                  int imageCount, EstimateDraft draft, AiOutcome<EstimateDraft> outcome) {
        PriceEstimate entity = new PriceEstimate();
        entity.setUserId(userId);
        entity.setTitle(title);
        entity.setDescription(description);
        entity.setCategoryId(dto.categoryId());
        entity.setCategoryName(category == null ? null : category.getName());
        entity.setImageUrl(dto.images() == null || dto.images().isEmpty()
                ? null : clip(dto.images().get(0), 255));
        entity.setImageCount(imageCount);
        entity.setTextHash(AiCallRecorder.shortHash(
                (title == null ? "" : title) + "|" + (description == null ? "" : description)
                        + "|" + dto.categoryId() + "|" + dto.conditionLevel()));
        entity.setConditionGrade(draft.condition());
        entity.setPriceLow(draft.low());
        entity.setPriceHigh(draft.high());
        entity.setPriceMid(heuristics.mid(draft.low(), draft.high()));
        entity.setConfidence(draft.confidence());
        entity.setReasons(toJson(draft.reasons()));
        entity.setSampleCount(draft.sampleCount());
        entity.setBasis(clip(draft.basis(), 255));
        entity.setSource(outcome.degraded() ? "rule" : "model");
        entity.setLogId(outcome.logId());
        entity.setAdopted(0);
        entity.setCreateTime(LocalDateTime.now());
        try {
            estimateMapper.insert(entity);
        } catch (Exception e) {
            log.warn("写入 AI 定价评估失败（是否已执行 mysql/init/06-ai-upgrade.sql？）：{}", e.getMessage());
            throw new BizException("AI 定价表未初始化，请管理员先执行 mysql/init/06-ai-upgrade.sql");
        }
        return entity;
    }

    /**
     * 解析卖家提交的「采用估价」请求。
     *
     * <p>只在商品发布/编辑事务里调用：校验归属与是否已被使用，
     * 返回要写入商品的字段。找不到或不属于当前用户时抛业务异常，
     * 避免「传了别人的估价 ID 却被静默忽略」。
     */
    public Adoption resolveAdoption(Long estimateId, Long userId, Long currentItemId) {
        if (estimateId == null) {
            return null;
        }
        PriceEstimate estimate = estimateMapper.selectById(estimateId);
        if (estimate == null) {
            throw new BizException("AI 估价记录不存在，请重新估价");
        }
        if (!userId.equals(estimate.getUserId())) {
            throw BizException.forbidden("不能使用他人的 AI 估价");
        }
        // 已被其它商品使用 → 拒绝；同一商品再次保存（编辑场景）允许复用
        if (estimate.getAdopted() != null && estimate.getAdopted() == 1
                && estimate.getItemId() != null && !estimate.getItemId().equals(currentItemId)) {
            throw new BizException("该 AI 估价已被使用，请重新估价");
        }
        return new Adoption(estimate.getEstimateId(), estimate.getConditionGrade(),
                estimate.getPriceLow(), estimate.getPriceMid(), estimate.getPriceHigh(),
                estimate.getSource());
    }

    /** 标记估价已采纳，并回填商品 ID（发布后调用） */
    public void markAdopted(Long estimateId, Long itemId, Long userId) {
        if (estimateId == null) {
            return;
        }
        PriceEstimate estimate = estimateMapper.selectById(estimateId);
        if (estimate == null || !userId.equals(estimate.getUserId())) {
            return;
        }
        PriceEstimate update = new PriceEstimate();
        update.setEstimateId(estimateId);
        update.setItemId(itemId);
        update.setAdopted(1);
        estimateMapper.updateById(update);
    }

    // ------------------------------------------------------------------ 工具

    private AiUpgradeVos.PriceEstimateVO toVo(PriceEstimate entity, AiOutcome<EstimateDraft> outcome) {
        AiUpgradeVos.PriceEstimateVO vo = new AiUpgradeVos.PriceEstimateVO();
        vo.setEstimateId(entity.getEstimateId());
        vo.setConditionGrade(entity.getConditionGrade());
        vo.setPriceLow(entity.getPriceLow());
        vo.setPriceMid(entity.getPriceMid());
        vo.setPriceHigh(entity.getPriceHigh());
        vo.setConfidence(entity.getConfidence());
        vo.setReasons(parseReasons(entity.getReasons()));
        vo.setSampleCount(entity.getSampleCount() == null ? 0 : entity.getSampleCount());
        vo.setBasis(entity.getBasis());
        vo.setSource(entity.getSource());
        vo.setDegradeNote(outcome.degradeNote());
        vo.setLogId(outcome.logId());
        vo.setAdopted(false);
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    private BigDecimal referenceMid(AiItemContextService.PriceStats sold,
                                    AiItemContextService.PriceStats onSale,
                                    BigDecimal askingPrice) {
        if (sold != null) {
            return sold.mid();
        }
        if (onSale != null) {
            return onSale.mid();
        }
        return askingPrice;
    }

    private String basis(AiItemContextService.PriceStats onSale, AiItemContextService.PriceStats sold) {
        List<String> parts = new ArrayList<>();
        if (onSale != null) {
            parts.add(onSale.basis());
        }
        if (sold != null) {
            parts.add(sold.basis());
        }
        return parts.isEmpty() ? "同类样本不足" : String.join("；", parts);
    }

    private int sampleCount(AiItemContextService.PriceStats onSale, AiItemContextService.PriceStats sold) {
        int count = 0;
        if (onSale != null) {
            count += onSale.sampleCount();
        }
        if (sold != null) {
            count += sold.sampleCount();
        }
        return count;
    }

    private List<String> trimReasons(List<String> reasons) {
        List<String> result = new ArrayList<>();
        for (String reason : reasons) {
            if (StringUtils.hasText(reason)) {
                result.add(clip(reason, 80));
            }
            if (result.size() >= MAX_REASONS) {
                break;
            }
        }
        return result;
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> parseReasons(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private BigDecimal number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal decimal(JsonNode node, String field) {
        BigDecimal value = number(node, field);
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP);
    }

    private List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            if (item != null && !item.isNull() && StringUtils.hasText(item.asText())) {
                result.add(item.asText().trim());
            }
        }
        return result;
    }

    private String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** 估价草稿（模型与规则两种实现共同的中间结构） */
    private record EstimateDraft(String condition, BigDecimal low, BigDecimal high,
                                 BigDecimal confidence, List<String> reasons,
                                 String basis, int sampleCount, boolean adjusted) {
    }

    /** 卖家采纳后要写入商品的字段 */
    public record Adoption(Long estimateId, String conditionGrade,
                           BigDecimal priceLow, BigDecimal priceMid, BigDecimal priceHigh,
                           String source) {
    }
}
