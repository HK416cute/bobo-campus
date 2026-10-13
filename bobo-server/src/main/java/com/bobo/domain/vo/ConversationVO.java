package com.bobo.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 私信会话视图（聊天列表）。
 *
 * @author bobo
 */
@Data
public class ConversationVO implements Serializable {

    private Long conversationId;
    private Long itemId;
    private String itemTitle;
    private String itemCover;
    private String itemPrice;
    /** 商品当前状态，用于在聊天列表提示「已售」 */
    private Integer itemStatus;

    private Long peerId;
    private String peerNickname;
    private String peerAvatar;
    private String peerCampus;

    private String lastMessage;
    private LocalDateTime lastTime;

    /** 当前登录用户在该会话中的未读数 */
    private Integer unread;
}
