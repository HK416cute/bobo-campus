package com.bobo.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单视图（订单列表与详情）。
 *
 * @author bobo
 */
@Data
public class OrderVO implements Serializable {

    private Long orderId;
    private String orderNo;
    private Long itemId;
    private String itemTitle;
    private String itemCover;

    private String price;
    private Integer quantity;
    private String totalAmount;

    private Integer tradeType;
    private String receiverInfo;
    private String tradePlace;
    private LocalDateTime tradeTime;
    private String remark;

    private String logisticsCompany;
    private String logisticsNo;

    private Integer status;
    private String statusText;
    private String cancelReason;

    private LocalDateTime payTime;
    private LocalDateTime deliverTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;

    private Long buyerId;
    private String buyerNickname;
    private String buyerPhone;

    private Long sellerId;
    private String sellerNickname;
    private String sellerPhone;

    /** 当前登录用户在该订单中的身份：buyer / seller */
    private String myRole;

    /** 当前登录用户是否已评价该订单 */
    private Boolean reviewed = false;

    /** 便于前端直接展示的金额（避免前端自己拼接） */
    public void setTotalAmount(BigDecimal amount) {
        this.totalAmount = amount == null ? null : amount.toPlainString();
    }

    public void setPrice(BigDecimal price) {
        this.price = price == null ? null : price.toPlainString();
    }
}
