package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 智能撮合单（买家 Agent × 卖家 Agent 多轮协商）。
 *
 * <p><b>人工确认原则</b>是这张表最重要的约束：协商结果只写进 {@code agreed_price}，
 * 必须 {@code buyerConfirmed} 与 {@code sellerConfirmed} 同时为 1 才进入「已达成」，
 * 系统绝不自动成交。技术规格书 6.1 把这条列在第一点，代码里也必须是硬约束。
 *
 * <p>状态机：
 * <pre>
 *   0 协商中 ──达成──→ 1 待双方确认 ──双方确认──→ 2 已达成 ──下单──→ 6 已下单
 *      │                   │
 *      ├──无法达成──→ 3 未达成
 *      └──取消/超时──→ 4 已取消 / 5 已过期
 * </pre>
 *
 * @author bobo
 */
@Data
@TableName("bobo_match_request")
public class MatchRequest implements Serializable {

    public static final int STATUS_NEGOTIATING = 0;
    public static final int STATUS_AWAITING_CONFIRM = 1;
    public static final int STATUS_AGREED = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_CANCELLED = 4;
    public static final int STATUS_EXPIRED = 5;
    public static final int STATUS_ORDERED = 6;

    public static final String SOURCE_MODEL = "model";
    public static final String SOURCE_RULE = "rule";
    public static final String SOURCE_MIXED = "mixed";

    @TableId(value = "match_id", type = IdType.AUTO)
    private Long matchId;

    private Long buyerId;
    private Long sellerId;
    private Long itemId;

    private Integer status;

    private BigDecimal budgetMin;
    private BigDecimal budgetMax;
    private String demandText;
    private String preferredTime;
    private String preferredPlace;
    /** 期望交易方式：1 当面 / 2 邮寄 */
    private Integer tradeType;

    /** 协商达成价（仅建议，需双方确认后才允许按此价下单） */
    private BigDecimal agreedPrice;
    private Integer rounds;
    private String summary;

    private Integer buyerConfirmed;
    private Integer sellerConfirmed;
    private String buyerNote;
    private String sellerNote;

    /** 协商来源：model / rule / mixed（部分轮次模型、部分轮次规则） */
    private String source;
    private Long logId;
    private LocalDateTime expireTime;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
