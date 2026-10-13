package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.bobo.domain.entity.Message;
import com.bobo.domain.vo.SimpleVos;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 私信消息 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 会话内的消息分页。
     * 注意：按时间倒序分页（最新的在第一页），前端渲染时再反转，
     * 这样「加载更多历史消息」符合聊天习惯。
     */
    IPage<SimpleVos.MessageVO> selectMessagePage(IPage<SimpleVos.MessageVO> page,
                                                 @Param("conversationId") Long conversationId);

    /** 把发给我的未读消息标记为已读 */
    @Update("""
            UPDATE bobo_message
               SET is_read = 1
             WHERE conversation_id = #{conversationId} AND to_id = #{userId} AND is_read = 0
            """)
    int markRead(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    /** 消息总数（后台概览） */
    @Select("SELECT COUNT(*) FROM bobo_message")
    long countAll();
}
