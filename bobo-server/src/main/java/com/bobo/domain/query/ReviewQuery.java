package com.bobo.domain.query;

import com.bobo.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评价列表查询条件。
 *
 * @author bobo
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReviewQuery extends PageQuery {

    private String keyword;

    private Long toId;

    private Long fromId;

    private Long itemId;

    /** 0 显示 / 1 隐藏 */
    private Integer status;
}
