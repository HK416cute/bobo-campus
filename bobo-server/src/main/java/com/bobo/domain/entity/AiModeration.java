package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 内容审核结果。
 *
 * <p>与现有 {@code bobo_report}（人工举报）互补：举报是<b>事后</b>、由人发起；
 * 本表是<b>事前</b>、由模型给出风险分，命中高风险的内容在发布时就带上标记，
 * 管理员在「待审核」列表里能直接看到模型理由（{@code reason}），而不是只看到一个红点。
 *
 * <p>{@code content_hash} 是幂等键：同一段内容重复审核直接命中已有结论，
 * 既不重复烧 token，也保证「同一内容两次结论不同」这种不可解释的情况不出现。
 *
 * @author bobo
 */
@Data
@TableName("bobo_ai_moderation")
public class AiModeration implements Serializable {

    public static final String TARGET_ITEM = "item";
    public static final String TARGET_REVIEW = "review";
    public static final String TARGET_MESSAGE = "message";

    /** 风险分级：0 低 / 1 中 / 2 高 */
    public static final int RISK_LOW = 0;
    public static final int RISK_MEDIUM = 1;
    public static final int RISK_HIGH = 2;

    @TableId(value = "moderation_id", type = IdType.AUTO)
    private Long moderationId;

    /** 审核对象类型：item / review / message */
    private String targetType;
    private Long targetId;

    /** 内容摘要，作为幂等键与缓存键 */
    private String contentHash;

    /** 0 低 / 1 中 / 2 高 */
    private Integer riskLevel;

    /** 风险类目，逗号分隔（如 违禁物品,虚假描述,联系方式引流） */
    private String riskCategories;

    /** 判定理由，给管理员看的可解释说明 */
    private String reason;

    /** 是否建议直接拦截（高风险），仍由管理员最终决定 */
    private Integer autoReject;

    /** 规则 or 模型，标明结论来源，便于评估模型增益 */
    private String decisionSource;

    /** 关联的模型调用流水 */
    private Long logId;

    private LocalDateTime createTime;
}
