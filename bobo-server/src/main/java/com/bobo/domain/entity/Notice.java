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
 * 公告与站内通知（user_id = 0 表示全站公告）。
 *
 * @author bobo
 */
@Data
@TableName("bobo_notice")
public class Notice implements Serializable {

    /** 全站公告 */
    public static final long USER_ALL = 0L;

    public static final String TYPE_SYSTEM = "system";
    public static final String TYPE_ORDER = "order";
    public static final String TYPE_AUDIT = "audit";
    public static final String TYPE_CHAT = "chat";

    @TableId(value = "notice_id", type = IdType.AUTO)
    private Long noticeId;

    private Long userId;
    private String title;
    private String content;
    private String type;
    private Long bizId;
    private Integer isRead;

    @TableLogic
    @JsonIgnore
    private Integer delFlag;

    private LocalDateTime createTime;
}
