package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 交易评价（双向）。
 *
 * @author bobo
 */
@Data
@TableName("bobo_review")
public class Review implements Serializable {

    public static final String ROLE_BUYER = "buyer";
    public static final String ROLE_SELLER = "seller";

    public static final int STATUS_VISIBLE = 0;
    public static final int STATUS_HIDDEN = 1;

    @TableId(value = "review_id", type = IdType.AUTO)
    private Long reviewId;

    private Long orderId;
    private Long itemId;
    private Long fromId;
    private Long toId;

    private Integer score;
    private String content;

    /** buyer 买家评卖家 / seller 卖家评买家 */
    private String role;

    /** 0 显示 / 1 隐藏 */
    private Integer status;

    @TableLogic
    @JsonIgnore
    private Integer delFlag;

    private LocalDateTime createTime;
}
