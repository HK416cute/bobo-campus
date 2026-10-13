package com.bobo.ai;

import com.bobo.domain.entity.AiCallLog;
import com.bobo.domain.entity.AiModeration;
import com.bobo.domain.vo.AiVos;
import com.bobo.mapper.AiModerationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 内容审核：给商品/评价/私信打风险分，联动现有的「0 待审核」流程。
 *
 * <p>与现有 {@code bobo_report}（人工举报）是互补关系，不是替代：
 * 举报是<b>事后</b>、由人发起；本服务是<b>事前</b>、由模型给出风险提示与可解释理由。
 * 管理员在「待审核」列表里看到的不再只是一个红点，而是"命中「代写论文」→ 学术作弊"。
 *
 * <p>三条实现纪律：
 * <ol>
 *   <li><b>先规则后模型</b>：本地关键词命中高风险直接出结论、不调模型。
 *       这既是成本闸门（脏流量不烧 token），也是降级兜底（模型挂了审核仍有结论）。
 *       绝不允许出现"AI 不可用 → 审核直接放行"，那等于把风险内容全放进站。</li>
 *   <li><b>幂等缓存</b>：以 {@code content_hash} 为键，同一段内容复用已有结论。
 *       既省钱，也保证"同一内容两次审核结论不同"这种不可解释的情况不出现。</li>
 *   <li><b>结论来源可区分</b>：{@code decision_source} 记录 rule 还是 model，
 *       这样"模型相比关键词规则多抓到了什么"是可度量的，而不是感觉。</li>
 * </ol>
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModerationService {

    private static final String SYSTEM_PROMPT = """
            你是校园二手交易平台的内容审核助手。判断用户提交的内容是否有风险。

            风险类目（riskCategories 只能从这里选，可多选）：
            违禁物品 / 学术作弊 / 欺诈风险 / 联系方式引流 / 不当内容

            风险分级：
            - 0 低：正常校园闲置内容
            - 1 中：需要人工看一眼，如要求私下转账、频繁引导加微信
            - 2 高：明显违规，如管制刀具、代写论文、涉及色情

            reason：一句话说明判定理由，要具体指出是哪句话、命中哪类风险，便于管理员快速判断。
            正常内容给 riskLevel=0、riskCategories=[]、reason 说明"未发现风险"。
            """;

    private final AiExecutor aiExecutor;
    private final SafeJsonParser jsonParser;
    private final AiKeywordRules rules;
    private final AiModerationMapper moderationMapper;

    /**
     * 审核一段内容。
     *
     * @param targetType item / review / message
     * @param content    待审核内容
     * @param targetId   对象ID，发布前预审时可为 null
     */
    public AiVos.ModerationVO moderate(String targetType, String content, Long targetId) {
        String normalizedType = normalizeType(targetType);
        String hash = AiCallRecorder.shortHash(content);

        // 1) 幂等：同一段内容已有结论就直接复用，不重复烧 token
        if (hash != null) {
            AiModeration cached = moderationMapper.selectLatestByHash(hash);
            if (cached != null) {
                log.debug("内容审核命中缓存：hash={}, riskLevel={}", hash, cached.getRiskLevel());
                return toVo(cached);
            }
        }

        // 2) 前置规则：高风险直接出结论，不调模型
        AiKeywordRules.RuleResult ruleResult = rules.evaluate(content);
        if (ruleResult.matched() && ruleResult.riskLevel() >= AiModeration.RISK_HIGH) {
            AiVos.ModerationVO vo = ruleResult.toVo(normalizedType, targetId,
                    "命中本地高风险关键词规则，未调用模型");
            save(normalizedType, targetId, hash, vo, null);
            aiExecutor.recordDegraded(AiCallLog.SCENE_MODERATION, vo.getDegradeNote(), content);
            return vo;
        }

        // 3) 模型判定（失败或未启用时自动回落到规则结论）
        AiOutcome<AiVos.ModerationVO> outcome = aiExecutor.execute(
                AiCallLog.SCENE_MODERATION,
                SYSTEM_PROMPT,
                "对象类型：" + normalizedType + "\n内容：\n" + content,
                raw -> parse(raw, normalizedType, targetId, content),
                () -> ruleResult.toVo(normalizedType, targetId,
                        ruleResult.matched() ? "模型不可用，使用本地关键词规则结论" : "模型不可用且未命中规则"));

        AiVos.ModerationVO vo = outcome.value();
        vo.setSource(outcome.source());
        vo.setDegradeNote(outcome.degradeNote());
        vo.setLogId(outcome.logId());
        if (vo.getRiskLevel() == null) {
            vo.setRiskLevel(AiModeration.RISK_LOW);
        }
        if (vo.getAutoReject() == null) {
            vo.setAutoReject(vo.getRiskLevel() >= AiModeration.RISK_HIGH);
        }
        // 4) 落库作为幂等缓存与后台列表数据源
        save(normalizedType, targetId, hash, vo, outcome.logId());
        return vo;
    }

    /** 后台审核结论分页 */
    public List<AiVos.ModerationItemVO> list(String targetType, Integer riskLevel) {
        var wrapper = com.baomidou.mybatisplus.core.toolkit.Wrappers.<AiModeration>lambdaQuery();
        if (StringUtils.hasText(targetType)) {
            wrapper.eq(AiModeration::getTargetType, targetType.trim());
        }
        if (riskLevel != null) {
            wrapper.ge(AiModeration::getRiskLevel, riskLevel);
        }
        wrapper.orderByDesc(AiModeration::getModerationId).last("LIMIT 100");
        List<AiModeration> rows = moderationMapper.selectList(wrapper);
        List<AiVos.ModerationItemVO> list = new ArrayList<>();
        for (AiModeration row : rows) {
            AiVos.ModerationItemVO item = new AiVos.ModerationItemVO();
            item.setModerationId(row.getModerationId());
            item.setTargetType(row.getTargetType());
            item.setTargetId(row.getTargetId());
            item.setRiskLevel(row.getRiskLevel());
            item.setRiskCategories(row.getRiskCategories());
            item.setReason(row.getReason());
            item.setAutoReject(row.getAutoReject());
            item.setDecisionSource(row.getDecisionSource());
            item.setCreateTime(row.getCreateTime() == null ? null : row.getCreateTime().toString());
            list.add(item);
        }
        return list;
    }

    /** 高风险商品数量，供后台角标 */
    public int highRiskItemCount() {
        try {
            return moderationMapper.countHighRiskItems();
        } catch (Exception e) {
            log.warn("统计高风险商品失败：{}", e.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------------ 解析与落库

    private AiVos.ModerationVO parse(String raw, String targetType, Long targetId, String content) {
        return jsonParser.parse(raw, node -> {
            AiVos.ModerationVO vo = new AiVos.ModerationVO();
            vo.setTargetType(targetType);
            vo.setTargetId(targetId);
            Integer level = intValue(node, "riskLevel");
            if (level == null || level < 0 || level > 2) {
                level = AiModeration.RISK_LOW;
            }
            vo.setRiskLevel(level);
            vo.setRiskCategories(normalizeCategories(node.get("riskCategories")));
            String reason = text(node, "reason");
            vo.setReason(StringUtils.hasText(reason) ? reason.trim() : "模型未给出理由");
            vo.setAutoReject(level >= AiModeration.RISK_HIGH);
            return vo;
        });
    }

    private void save(String targetType, Long targetId, String hash, AiVos.ModerationVO vo, Long logId) {
        if (hash == null) {
            return;
        }
        try {
            AiModeration entity = new AiModeration();
            entity.setTargetType(targetType);
            entity.setTargetId(targetId);
            entity.setContentHash(hash);
            entity.setRiskLevel(vo.getRiskLevel());
            entity.setRiskCategories(vo.getRiskCategories() == null ? null
                    : String.join(",", vo.getRiskCategories()));
            entity.setReason(vo.getReason());
            entity.setAutoReject(Boolean.TRUE.equals(vo.getAutoReject()) ? 1 : 0);
            entity.setDecisionSource(vo.getSource());
            entity.setLogId(logId);
            entity.setCreateTime(java.time.LocalDateTime.now());
            moderationMapper.insert(entity);
        } catch (Exception e) {
            // 缓存写失败不影响本次审核结论的返回，只是下次还要重算
            log.warn("写入 AI 审核结论失败：hash={}, cause={}", hash, e.getMessage());
        }
    }

    private AiVos.ModerationVO toVo(AiModeration cached) {
        AiVos.ModerationVO vo = new AiVos.ModerationVO();
        vo.setTargetType(cached.getTargetType());
        vo.setTargetId(cached.getTargetId());
        vo.setRiskLevel(cached.getRiskLevel());
        vo.setRiskCategories(cached.getRiskCategories() == null || cached.getRiskCategories().isBlank()
                ? List.of() : List.of(cached.getRiskCategories().split(",")));
        vo.setReason(cached.getReason());
        vo.setAutoReject(cached.getAutoReject() != null && cached.getAutoReject() == 1);
        vo.setSource(cached.getDecisionSource());
        vo.setDegradeNote("命中审核缓存，复用已有结论");
        vo.setLogId(cached.getLogId());
        return vo;
    }

    // ------------------------------------------------------------------ 小工具

    private String normalizeType(String targetType) {
        if (!StringUtils.hasText(targetType)) {
            return AiModeration.TARGET_ITEM;
        }
        String trimmed = targetType.trim();
        return List.of(AiModeration.TARGET_ITEM, AiModeration.TARGET_REVIEW,
                AiModeration.TARGET_MESSAGE).contains(trimmed) ? trimmed : AiModeration.TARGET_ITEM;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Integer intValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> normalizeCategories(JsonNode node) {
        List<String> allowed = List.of(AiKeywordRules.CATEGORY_ILLEGAL, AiKeywordRules.CATEGORY_ACADEMIC,
                AiKeywordRules.CATEGORY_FRAUD, AiKeywordRules.CATEGORY_CONTACT,
                AiKeywordRules.CATEGORY_ADULT);
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            String value = item.asText().trim();
            // 只接受已知类目，防止模型自造类目把后台筛选项搞乱
            if (allowed.contains(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }
}
