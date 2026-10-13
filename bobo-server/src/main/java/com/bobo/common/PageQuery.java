package com.bobo.common;

import lombok.Data;

/**
 * 分页查询基类，所有列表查询 DTO 继承它。
 *
 * @author bobo
 */
@Data
public class PageQuery {

    /** 页码，从 1 开始 */
    private Integer pageNum = 1;
    /** 每页条数，最大 100 */
    private Integer pageSize = 10;

    public long safePageNum() {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    public long safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return 10L;
        }
        return Math.min(pageSize, 100);
    }
}
