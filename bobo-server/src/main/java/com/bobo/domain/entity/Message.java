package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 私信消息。
 *
 * @author bobo
 */
@Data
@TableName("bobo_message")
public class Message implements Serializable {

    @TableId(value = "message_id", type = IdType.AUTO)
    private Long messageId;

    private Long conversationId;
    private Long fromId;
    private Long toId;
    private String content;

    /** 0 未读 / 1 已读 */
    private Integer isRead;

    private LocalDateTime createTime;
}
