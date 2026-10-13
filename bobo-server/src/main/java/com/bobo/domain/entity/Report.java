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
 * 举报（商品 / 用户 / 评价）。
 *
 * @author bobo
 */
@Data
@TableName("bobo_report")
public class Report implements Serializable {

    public static final String TARGET_ITEM = "item";
    public static final String TARGET_USER = "user";
    public static final String TARGET_REVIEW = "review";

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_HANDLED = 1;
    public static final int STATUS_REJECTED = 2;

    @TableId(value = "report_id", type = IdType.AUTO)
    private Long reportId;

    private Long userId;
    private String targetType;
    private Long targetId;
    private String reason;
    private String detail;

    /** 0 待处理 / 1 已处理 / 2 已驳回 */
    private Integer status;

    private String handleRemark;
    private Long handleBy;
    private LocalDateTime handleTime;

    @TableLogic
    @JsonIgnore
    private Integer delFlag;

    private LocalDateTime createTime;
}
