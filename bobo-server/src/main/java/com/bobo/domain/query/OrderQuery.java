package com.bobo.domain.query;

import com.bobo.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单列表查询条件。
 *
 * @author bobo
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderQuery extends PageQuery {

    /** 订单号 / 商品标题关键词 */
    private String keyword;

    private Integer status;

    private Long buyerId;

    private Long sellerId;

    /** 当前登录用户视角：buy 我买到的 / sell 我卖出的 / all 全部（管理员用） */
    private String role = "buy";
}
