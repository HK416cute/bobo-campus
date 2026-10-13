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
 * 商品分类（支持二级，parent_id = 0 为顶级）。
 *
 * @author bobo
 */
@Data
@TableName("bobo_category")
public class Category implements Serializable {

    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED = 1;

    @TableId(value = "category_id", type = IdType.AUTO)
    private Long categoryId;

    private Long parentId;
    private String name;

    /** 图标（emoji 或图标名） */
    private String icon;

    private Integer sort;

    /** 0 启用 / 1 停用 */
    private Integer status;

    @TableLogic
    @JsonIgnore
    private Integer delFlag;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
