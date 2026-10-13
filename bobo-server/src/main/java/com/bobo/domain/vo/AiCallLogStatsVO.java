package com.bobo.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * AI 调用流水按场景聚合的统计行（Mapper 直接映射）。
 * <p>
 * 为与既有统计 SQL 的约定一致，列别名一律用驼峰。
 *
 * @author bobo
 */
@Data
public class AiCallLogStatsVO {

    private String scene;

    /** 调用总次数 */
    private Long calls;

    /** 成功次数 */
    private Long successCount;

    /** 降级次数（走规则结果） */
    private Long degradedCount;

    /** 失败次数 */
    private Long failedCount;

    /** 平均耗时（毫秒） */
    private Long avgLatencyMs;

    /** 累计估算成本（元） */
    private BigDecimal estimatedCost;

    /** 累计 token */
    private Long totalTokens;
}
