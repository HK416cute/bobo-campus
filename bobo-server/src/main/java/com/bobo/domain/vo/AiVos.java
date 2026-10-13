package com.bobo.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 相关视图对象集合。
 * <p>
 * 与 {@code SimpleVos} 同理用普通类 + Lombok {@code @Data} 而非 record：
 * 这些对象既可能被 MyBatis 直接映射（审核结论分页），也会被 Jackson 序列化，
 * 有 getter/setter 最省心。
 * <p>
 * <b>每个 VO 都带 {@code source} 字段</b>：前端据此标注「AI 生成」还是「规则推算」。
 * 这是刻意设计——用户有权知道结论是谁给的，也便于线上区分「模型变笨」与「降级生效」。
 *
 * @author bobo
 */
public final class AiVos {

    private AiVos() {
    }

    /** 发布助手返回：结构化草稿建议 */
    @Data
    public static class ItemDraftVO {
        private String title;
        private String description;
        private Long categoryId;
        private String categoryName;
        private String conditionLevel;
        private List<String> tags;
        private PriceSuggestionVO priceSuggestion;
        /** 风险标记，如 违禁物品 / 虚假描述；为空表示未发现风险 */
        private List<String> riskFlags;
        /** ai 模型生成 / rule 规则推算 */
        private String source;
        /** 降级原因，未降级时为 null */
        private String degradeNote;
        /** 关联调用流水，前端反馈采纳情况时回传 */
        private Long logId;
        /** 模型耗时（毫秒） */
        private long latencyMs;
    }

    /** 价格建议：全部来自库内同类商品的真实分位，不是模型编的数字 */
    @Data
    public static class PriceSuggestionVO {
        private BigDecimal low;
        private BigDecimal mid;
        private BigDecimal high;
        /** 参与统计的样本量 */
        private int sampleCount;
        /** 样本来源说明，形如「分类「平板电脑」在售 37 件」 */
        private String basis;
    }

    /** 检索意图解析结果：可直接回填到搜索栏与商品的查询条件 */
    @Data
    public static class SearchSlotsVO {
        private String keyword;
        private Long categoryId;
        private String categoryName;
        private BigDecimal minPrice;
        private BigDecimal maxPrice;
        private String conditionLevel;
        private String campus;
        /** 0 都支持 / 1 仅当面 / 2 仅邮寄 */
        private Integer tradeType;
        private String sortBy;
        /** 模型对本次解析的说明，展示在搜索框下方 */
        private String explanation;
        private String source;
        private String degradeNote;
        private Long logId;
        private long latencyMs;
    }

    /** 内容风险判定结果 */
    @Data
    public static class ModerationVO {
        private String targetType;
        private Long targetId;
        /** 0 低 / 1 中 / 2 高 */
        private Integer riskLevel;
        private List<String> riskCategories;
        private String reason;
        /** 是否建议直接拦截（最终仍由管理员决定） */
        private Boolean autoReject;
        /** rule 关键词规则 / model 模型判定 */
        private String source;
        private String degradeNote;
        private Long logId;
    }

    /** 审核结论分页项（供后台列表展示） */
    @Data
    public static class ModerationItemVO {
        private Long moderationId;
        private String targetType;
        private Long targetId;
        private Integer riskLevel;
        private String riskCategories;
        private String reason;
        private Integer autoReject;
        private String decisionSource;
        private String createTime;
    }

    /** AI 效果面板：调用量、成功率、耗时、成本、采纳率 */
    @Data
    public static class AiMetricsVO {
        /** 总调用次数 */
        private long totalCalls;
        /** 成功 / 降级 / 失败 */
        private long successCount;
        private long degradedCount;
        private long failedCount;
        /** 成功率 = success / total */
        private double successRate;
        /** 降级率，衡量"模型到底有没有在干活" */
        private double degradeRate;
        /** 耗时分位（毫秒），只统计成功调用 */
        private long p50LatencyMs;
        private long p95LatencyMs;
        private long p99LatencyMs;
        /** 累计估算成本（元） */
        private BigDecimal estimatedCost;
        /** 累计 token */
        private long totalTokens;
        /** 发布建议采纳率 */
        private double publishDraftAcceptRate;
        private long publishDraftTotal;
        private long publishDraftAccepted;
        /** 检索改写采纳率 */
        private double searchQueryAcceptRate;
        private long searchQueryTotal;
        private long searchQueryAccepted;
        /** 当前是否处于降级模式（无 key / 已关闭） */
        private boolean degraded;
        /** 降级原因 */
        private String degradeReason;
        /** 分场景明细 */
        private List<SceneMetricsVO> scenes;
    }

    /** 单场景指标 */
    @Data
    public static class SceneMetricsVO {
        private String scene;
        private long calls;
        private long successCount;
        private long degradedCount;
        private long failedCount;
        private long avgLatencyMs;
        private BigDecimal estimatedCost;
    }

    /** 运营周报 */
    @Data
    public static class AdminReportVO {
        /** 自然语言周报正文 */
        private String report;
        /** 结构化事实（模型只允许基于这些数字写报告，避免编造） */
        private java.util.Map<String, Object> facts;
        private String source;
        private String degradeNote;
        private Long logId;
        private long latencyMs;
    }
}
