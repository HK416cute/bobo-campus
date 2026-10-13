package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 建议反馈：记录「模型建议了什么」与「用户最终用了什么」。
 *
 * <p>这是本项目所有效果指标的<b>唯一事实来源</b>。简历上写的
 * 「AI 发布建议采纳率 X%」就由 {@code accepted} 的占比算出来，而不是估算。
 * 同时 {@code suggested_value} / {@code final_value} 成对留存，
 * 使「建议偏差」可分析——例如发现模型对成色判断偏乐观，就能针对性改提示词。
 *
 * @author bobo
 */
@Data
@TableName("bobo_ai_suggestion_feedback")
public class AiSuggestionFeedback implements Serializable {

    public static final String TYPE_PUBLISH_DRAFT = "publish_draft";
    public static final String TYPE_SEARCH_QUERY = "search_query";

    @TableId(value = "feedback_id", type = IdType.AUTO)
    private Long feedbackId;

    /** 建议类型：publish_draft 发布草稿 / search_query 检索改写 */
    private String suggestionType;

    /** 归属对象：商品ID / 订单ID 等，可为空 */
    private Long targetId;

    private Long userId;

    /** 是否采纳：1 采纳 / 0 未采纳 */
    private Integer accepted;

    /** 建议值（如建议标题、建议分类），脱敏后截断存储 */
    private String suggestedValue;
    /** 用户最终提交的值，与建议值对比即为偏差 */
    private String finalValue;

    /** 关联的调用流水，便于回溯是哪次模型输出 */
    private Long logId;

    private LocalDateTime createTime;
}
