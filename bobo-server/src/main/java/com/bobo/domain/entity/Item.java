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
 * 商品（闲置宝贝）。
 *
 * @author bobo
 */
@Data
@TableName("bobo_item")
public class Item implements Serializable {

    /** 商品状态 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_ON_SALE = 1;
    public static final int STATUS_SOLD = 2;
    public static final int STATUS_OFF_SHELF = 3;
    public static final int STATUS_REJECTED = 4;

    /** 交易方式 */
    public static final int TRADE_BOTH = 0;
    public static final int TRADE_FACE = 1;
    public static final int TRADE_MAIL = 2;

    @TableId(value = "item_id", type = IdType.AUTO)
    private Long itemId;

    private String title;
    private String description;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Long categoryId;
    private Long sellerId;

    /** 封面图URL */
    private String cover;

    /** 成色 */
    private String conditionLevel;

    /** 0 都支持 / 1 仅当面 / 2 仅邮寄 */
    private Integer tradeType;

    private String campus;
    private String location;

    /** 0 待审核 / 1 在售 / 2 已售 / 3 已下架 / 4 已驳回 */
    private Integer status;

    private String rejectReason;
    private Integer viewCount;
    private Integer wantCount;

    private LocalDateTime auditTime;
    private Long auditBy;
    private LocalDateTime soldTime;

    @TableLogic
    @JsonIgnore
    private Integer delFlag;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
