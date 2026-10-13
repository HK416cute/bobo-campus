package com.bobo.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品卡片视图（列表页 / 首页瀑布流用，已裁剪掉长描述）。
 *
 * @author bobo
 */
@Data
public class ItemCardVO implements Serializable {

    private Long itemId;
    private String title;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private String cover;
    private String conditionLevel;
    private Integer tradeType;
    private Integer status;
    private String campus;
    private String location;
    private Integer viewCount;
    private Integer wantCount;
    private LocalDateTime createTime;

    private Long categoryId;
    private String categoryName;

    private Long sellerId;
    private String sellerNickname;
    private String sellerAvatar;
    private String sellerCampus;
    private Integer sellerCreditScore;
    private Integer sellerVerifyStatus;

    /** 当前登录用户是否已"想要" */
    private Boolean favorited = false;
}
