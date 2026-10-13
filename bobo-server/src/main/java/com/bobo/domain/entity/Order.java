package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单。状态机：
 * <pre>
 *   0 待付款 → 1 待发货 → 2 待收货 → 3 待评价 → 4 已完成
 *   5 已取消（买家/卖家/超时）；未完成态可申请退款 6 退款中 → 7 已退款
 *   当面交易跳过 1/2：0 待付款 → 3 待评价 → 4 已完成
 * </pre>
 * 常量直接写在实体内部，避免多一个无意义的常量类。
 *
 * @author bobo
 */
@Data
@TableName("bobo_order")
public class Order implements Serializable {

    /** 待付款 */
    public static final int STATUS_UNPAID = 0;
    /** 待发货 */
    public static final int STATUS_WAIT_DELIVER = 1;
    /** 待收货 */
    public static final int STATUS_WAIT_RECEIVE = 2;
    /** 待评价 */
    public static final int STATUS_WAIT_REVIEW = 3;
    /** 已完成 */
    public static final int STATUS_FINISHED = 4;
    /** 已取消 */
    public static final int STATUS_CANCELLED = 5;
    /** 退款中 */
    public static final int STATUS_REFUNDING = 6;
    /** 已退款 */
    public static final int STATUS_REFUNDED = 7;

    /** 交易方式 */
    public static final int TRADE_FACE = 1;
    public static final int TRADE_MAIL = 2;

    @TableId(value = "order_id", type = IdType.AUTO)
    private Long orderId;

    private String orderNo;
    private Long itemId;
    private Long buyerId;
    private Long sellerId;

    /** 下单时的商品快照，避免商品被改价后订单信息失真 */
    private String itemTitle;
    private String itemCover;

    private BigDecimal price;
    private Integer quantity;
    private BigDecimal totalAmount;

    /** 1 当面交易 / 2 邮寄 */
    private Integer tradeType;

    private Long addressId;
    private String receiverInfo;
    private String tradePlace;
    private LocalDateTime tradeTime;
    private String remark;

    private String logisticsCompany;
    private String logisticsNo;

    private Integer status;
    private String cancelReason;

    private LocalDateTime payTime;
    private LocalDateTime deliverTime;
    private LocalDateTime finishTime;

    @TableLogic
    @JsonIgnore
    private Integer delFlag;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 状态中文名，便于前端直接展示（不落库） */
    @JsonIgnore
    public String statusText() {
        return switch (status == null ? -1 : status) {
            case STATUS_UNPAID -> "待付款";
            case STATUS_WAIT_DELIVER -> "待发货";
            case STATUS_WAIT_RECEIVE -> "待收货";
            case STATUS_WAIT_REVIEW -> "待评价";
            case STATUS_FINISHED -> "已完成";
            case STATUS_CANCELLED -> "已取消";
            case STATUS_REFUNDING -> "退款中";
            case STATUS_REFUNDED -> "已退款";
            default -> "未知";
        };
    }
}
