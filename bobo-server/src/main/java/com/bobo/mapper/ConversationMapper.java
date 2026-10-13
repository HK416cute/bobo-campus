package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.bobo.domain.entity.Conversation;
import com.bobo.domain.query.ConversationQuery;
import com.bobo.domain.vo.ConversationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 私信会话 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /** 我的聊天列表（含对方信息、商品快照、我的未读数） */
    IPage<ConversationVO> selectConversationPage(IPage<ConversationVO> page,
                                                 @Param("q") ConversationQuery query,
                                                 @Param("currentUserId") Long currentUserId);

    /** 会话详情（校验归属用） */
    @Select("SELECT * FROM bobo_conversation WHERE conversation_id = #{id} AND del_flag = 0")
    Conversation selectAliveById(@Param("id") Long id);

    /** 某个用户在全部会话中的未读总数 */
    @Select("""
            SELECT IFNULL(SUM(CASE WHEN buyer_id = #{userId} THEN buyer_unread ELSE 0 END), 0)
                 + IFNULL(SUM(CASE WHEN seller_id = #{userId} THEN seller_unread ELSE 0 END), 0)
              FROM bobo_conversation
             WHERE del_flag = 0 AND (buyer_id = #{userId} OR seller_id = #{userId})
            """)
    int countUnread(@Param("userId") Long userId);

    /** 清理未读（打开会话时调用，按身份清对应的一侧） */
    @org.apache.ibatis.annotations.Update("""
            UPDATE bobo_conversation
               SET buyer_unread  = CASE WHEN buyer_id  = #{userId} THEN 0 ELSE buyer_unread  END,
                   seller_unread = CASE WHEN seller_id = #{userId} THEN 0 ELSE seller_unread END
             WHERE conversation_id = #{conversationId}
            """)
    int clearUnread(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    /** 找出某用户与某商品、某卖家的现有会话 */
    @Select("""
            SELECT * FROM bobo_conversation
             WHERE item_id = #{itemId} AND buyer_id = #{buyerId} AND seller_id = #{sellerId} AND del_flag = 0
             LIMIT 1
            """)
    Conversation selectByItemAndUsers(@Param("itemId") Long itemId,
                                      @Param("buyerId") Long buyerId,
                                      @Param("sellerId") Long sellerId);

    /** 后台：会话总量 */
    @Select("SELECT COUNT(*) FROM bobo_conversation WHERE del_flag = 0")
    long countAll();

    /** 某商品相关的会话（下架/删除商品时清理） */
    @Select("SELECT conversation_id FROM bobo_conversation WHERE item_id = #{itemId} AND del_flag = 0")
    List<Long> selectIdsByItemId(@Param("itemId") Long itemId);
}
