package com.bobo.domain.query;

import com.bobo.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 举报列表查询条件。
 *
 * @author bobo
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReportQuery extends PageQuery {

    /** item / user / review */
    private String targetType;

    /** 0 待处理 / 1 已处理 / 2 已驳回 */
    private Integer status;
}
