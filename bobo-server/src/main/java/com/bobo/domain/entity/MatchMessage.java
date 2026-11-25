package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 撮合协商中的一条消息（对应技术规格书数据模型的 ConversationLog）。
 *
 * <p>每条消息都记 {@code source}：让用户能分清这句话是模型 Agent 说的还是规则引擎推的。
 * 这和教育用户「AI 不是万能的」同样重要——规则兜底时不能假装是模型在谈。
 *
 * @author bobo
 */
@Data
@TableName("bobo_match_message")
public class MatchMessage implements Serializable {

    public static final String SENDER_BUYER = "buyer_agent";
    public static final String SENDER_SELLER = "seller_agent";
    public static final String SENDER_SYSTEM = "system";

    public static final String ACTION_OFFER = "offer";
    public static final String ACTION_COUNTER = "counter";
    public static final String ACTION_ACCEPT = "accept";
    public static final String ACTION_REJECT = "reject";
    public static final String ACTION_NOTE = "note";

    @TableId(value = "message_id", type = IdType.AUTO)
    private Long messageId;

    private Long matchId;
    private Integer roundNo;
    /** buyer_agent / seller_agent / system */
    private String sender;
    /** offer / counter / accept / reject / note */
    private String action;
    private BigDecimal price;
    private String content;
    /** 本条由模型还是规则生成：model / rule */
    private String source;
    private Long logId;
    private LocalDateTime createTime;
}
