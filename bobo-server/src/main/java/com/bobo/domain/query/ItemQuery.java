package com.bobo.domain.query;

import com.bobo.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 商品列表查询条件（前台搜索页 / 后台商品管理共用部分字段）。
 *
 * @author bobo
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ItemQuery extends PageQuery {

    /** 关键词，匹配标题与描述 */
    private String keyword;

    /**
     * 后台专用关键词：同时匹配商品ID（纯数字时）与卖家昵称。
     * 与 keyword 分开是为了让前台搜索的语义保持单一。
     */
    private String adminKeyword;

    private Long categoryId;

    private Long sellerId;

    /** 商品状态：0 待审核 / 1 在售 / 2 已售 / 3 已下架 / 4 已驳回；null 为全部 */
    private Integer status;

    private BigDecimal minPrice;
    private BigDecimal maxPrice;

    private String campus;

    /** 成色 */
    private String conditionLevel;

    /** 交易方式：0/1/2 */
    private Integer tradeType;

    /**
     * 排序方式：
     * new 最新发布 / priceAsc 价格升 / priceDesc 价格降 / want 最想要 / hot 最多浏览
     */
    private String sortBy = "new";

    /** 仅看当前登录用户收藏的商品（"我的想要"） */
    private Boolean onlyFavorite;

    /** 排除某个用户（用于「他还在卖的其他宝贝」） */
    private Long excludeSellerId;
}
