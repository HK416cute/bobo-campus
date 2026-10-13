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
 * 私信会话（买家 ↔ 卖家，围绕某件商品唯一）。
 *
 * @author bobo
 */
@Data
@TableName("bobo_conversation")
public class Conversation implements Serializable {

    @TableId(value = "conversation_id", type = IdType.AUTO)
    private Long conversationId;

    private Long itemId;
    private Long buyerId;
    private Long sellerId;

    private String lastMessage;
    private LocalDateTime lastTime;

    private Integer buyerUnread;
    private Integer sellerUnread;

    @TableLogic
    @JsonIgnore
    private Integer delFlag;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
