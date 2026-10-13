package com.bobo.ai;

import com.bobo.domain.entity.AiSuggestionFeedback;
import com.bobo.mapper.AiSuggestionFeedbackMapper;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 建议采纳反馈记录器。
 *
 * <p>这是整个 AI 模块里<b>唯一能产出"效果"指标</b>的地方：
 * 采纳率 = {@code accepted=1} 的条数 / 全部条数，直接由这张表算出。
 * 没有它，简历上写"采纳率 78%"就只能是估的，面试一问"怎么算的"就露馅。
 *
 * <p>同时留存 {@code suggestedValue} 与 {@code finalValue} 的成对数据，
 * 让"模型建议偏差"可分析——例如发现模型总把成色判乐观，
 * 就能针对性地改提示词，而不是笼统地说"效果不好"。
 *
 * <p><b>失败不影响主流程</b>：反馈是统计用途，写失败只记 warn。
 * 用户点"采纳"的主要目的是填表单，不该因为埋点失败而报错。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFeedbackRecorder {

    /** 存库前截断，避免前端传入超长文本把列撑爆（VARCHAR(500)） */
    private static final int MAX_VALUE_LEN = 500;

    private final AiSuggestionFeedbackMapper feedbackMapper;

    /**
     * 记录一条采纳反馈。
     *
     * @param suggestionType 建议类型，见 {@link AiSuggestionFeedback}
     * @param accepted       是否采纳
     * @param suggestedValue 建议值
     * @param finalValue     用户最终提交的值
     * @param targetId       归属对象ID，可空
     * @param logId          关联的调用流水，可空
     */
    public void record(String suggestionType, boolean accepted, String suggestedValue,
                       String finalValue, Long targetId, Long logId) {
        try {
            AiSuggestionFeedback entity = new AiSuggestionFeedback();
            entity.setSuggestionType(truncate(suggestionType, 32));
            entity.setUserId(AuthContext.userIdOrNull());
            entity.setAccepted(accepted ? 1 : 0);
            entity.setSuggestedValue(truncate(suggestedValue, MAX_VALUE_LEN));
            entity.setFinalValue(truncate(finalValue, MAX_VALUE_LEN));
            entity.setTargetId(targetId);
            entity.setLogId(logId);
            entity.setCreateTime(java.time.LocalDateTime.now());
            feedbackMapper.insert(entity);
        } catch (Exception e) {
            log.warn("记录 AI 建议反馈失败（不影响主流程）：type={}, cause={}",
                    suggestionType, e.getMessage());
        }
    }

    /** 校验建议类型是否为已知类型，避免统计口径被前端随意传值污染 */
    public boolean isKnownType(String suggestionType) {
        return AiSuggestionFeedback.TYPE_PUBLISH_DRAFT.equals(suggestionType)
                || AiSuggestionFeedback.TYPE_SEARCH_QUERY.equals(suggestionType);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
