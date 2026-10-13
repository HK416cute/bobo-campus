package com.bobo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bobo.common.PageResult;
import com.bobo.common.exception.BizException;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.Conversation;
import com.bobo.domain.entity.Item;
import com.bobo.domain.entity.Message;
import com.bobo.domain.entity.User;
import com.bobo.domain.query.ConversationQuery;
import com.bobo.domain.vo.ConversationVO;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.mapper.ConversationMapper;
import com.bobo.mapper.ItemMapper;
import com.bobo.mapper.MessageMapper;
import com.bobo.mapper.UserMapper;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 私信服务：会话创建、消息收发、未读维护。
 * <p>
 * 设计取舍：不做 WebSocket，纯 HTTP 轮询（默认 8 秒一次）。
 * 校园场景并发低，轮询实现简单、无需维护长连接与在线状态，
 * 前端在聊天页与顶栏角标各自轮询即可。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final int PREVIEW_LENGTH = 60;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ItemMapper itemMapper;
    private final UserMapper userMapper;
    private final NotificationService notificationService;

    /** 从商品详情点「聊一聊」：找到或创建会话，可选地带上第一句话 */
    @Transactional(rollbackFor = Exception.class)
    public ConversationVO openOrCreate(Dtos.ConversationOpenDTO dto) {
        Long userId = AuthContext.userId();
        Item item = itemMapper.selectById(dto.itemId());
        if (item == null) {
            throw BizException.notFound("商品不存在或已被删除");
        }
        if (userId.equals(item.getSellerId())) {
            throw new BizException("不能和自己聊天");
        }

        Conversation conversation = conversationMapper.selectByItemAndUsers(dto.itemId(), userId, item.getSellerId());
        if (conversation == null) {
            conversation = new Conversation();
            conversation.setItemId(item.getItemId());
            conversation.setBuyerId(userId);
            conversation.setSellerId(item.getSellerId());
            conversation.setBuyerUnread(0);
            conversation.setSellerUnread(0);
            conversation.setDelFlag(0);
            conversationMapper.insert(conversation);
        }

        if (StringUtils.hasText(dto.content())) {
            doSend(conversation, userId, dto.content().trim());
        }
        return buildVO(conversation, userId);
    }

    /** 我的会话列表 */
    public PageResult<ConversationVO> page(ConversationQuery query) {
        Page<ConversationVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<ConversationVO> result = conversationMapper.selectConversationPage(page, query, AuthContext.userId());
        return PageResult.of(result);
    }

    /** 进入会话：校验归属并把发给我的消息置为已读 */
    @Transactional(rollbackFor = Exception.class)
    public ConversationVO enter(Long conversationId) {
        Long userId = AuthContext.userId();
        Conversation conversation = requireMember(conversationId, userId);
        conversationMapper.clearUnread(conversationId, userId);
        messageMapper.markRead(conversationId, userId);
        return buildVO(conversation, userId);
    }

    /** 会话内的消息分页（时间倒序，第一页是最新消息） */
    public PageResult<SimpleVos.MessageVO> messages(Long conversationId, Integer pageNum, Integer pageSize) {
        Long userId = AuthContext.userId();
        requireMember(conversationId, userId);
        long current = pageNum == null || pageNum < 1 ? 1 : pageNum;
        long size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        Page<SimpleVos.MessageVO> page = new Page<>(current, size);
        IPage<SimpleVos.MessageVO> result = messageMapper.selectMessagePage(page, conversationId);
        return PageResult.of(result);
    }

    /** 发送消息 */
    @Transactional(rollbackFor = Exception.class)
    public SimpleVos.MessageVO send(Dtos.MessageDTO dto) {
        Long userId = AuthContext.userId();
        Conversation conversation = requireMember(dto.conversationId(), userId);
        Message message = doSend(conversation, userId, dto.content().trim());

        User from = userMapper.selectById(userId);
        SimpleVos.MessageVO vo = new SimpleVos.MessageVO();
        vo.setMessageId(message.getMessageId());
        vo.setConversationId(message.getConversationId());
        vo.setFromId(userId);
        vo.setFromNickname(from == null ? null : from.getNickname());
        vo.setFromAvatar(from == null ? null : from.getAvatar());
        vo.setToId(message.getToId());
        vo.setContent(message.getContent());
        vo.setIsRead(message.getIsRead());
        vo.setCreateTime(message.getCreateTime());
        return vo;
    }

    /** 未读消息总数 */
    public long unreadCount() {
        return conversationMapper.countUnread(AuthContext.userId());
    }

    // ------------------------------------------------------------------ 内部方法

    private Message doSend(Conversation conversation, Long fromId, String content) {
        Long toId = fromId.equals(conversation.getBuyerId())
                ? conversation.getSellerId()
                : conversation.getBuyerId();

        Message message = new Message();
        message.setConversationId(conversation.getConversationId());
        message.setFromId(fromId);
        message.setToId(toId);
        message.setContent(content);
        message.setIsRead(0);
        messageMapper.insert(message);

        Conversation update = new Conversation();
        update.setConversationId(conversation.getConversationId());
        update.setLastMessage(preview(content));
        update.setLastTime(LocalDateTime.now());
        if (fromId.equals(conversation.getBuyerId())) {
            update.setSellerUnread((conversation.getSellerUnread() == null ? 0 : conversation.getSellerUnread()) + 1);
        } else {
            update.setBuyerUnread((conversation.getBuyerUnread() == null ? 0 : conversation.getBuyerUnread()) + 1);
        }
        conversationMapper.updateById(update);

        // 会话内存量同步，避免同一请求里再次发送时计数错乱
        conversation.setLastMessage(update.getLastMessage());
        conversation.setLastTime(update.getLastTime());
        conversation.setSellerUnread(update.getSellerUnread() == null
                ? conversation.getSellerUnread() : update.getSellerUnread());
        conversation.setBuyerUnread(update.getBuyerUnread() == null
                ? conversation.getBuyerUnread() : update.getBuyerUnread());

        notificationService.notify("你收到一条新私信", preview(content),
                com.bobo.domain.entity.Notice.TYPE_CHAT, conversation.getConversationId(), toId);
        return message;
    }

    /** 会话成员校验 */
    public Conversation requireMember(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.selectAliveById(conversationId);
        if (conversation == null) {
            throw BizException.notFound("会话不存在");
        }
        if (!userId.equals(conversation.getBuyerId()) && !userId.equals(conversation.getSellerId())) {
            throw BizException.forbidden("你不在该会话中");
        }
        return conversation;
    }

    private ConversationVO buildVO(Conversation conversation, Long userId) {
        ConversationVO vo = new ConversationVO();
        vo.setConversationId(conversation.getConversationId());
        vo.setItemId(conversation.getItemId());
        vo.setLastMessage(conversation.getLastMessage());
        vo.setLastTime(conversation.getLastTime());

        boolean isBuyer = userId.equals(conversation.getBuyerId());
        vo.setUnread(isBuyer ? conversation.getBuyerUnread() : conversation.getSellerUnread());

        Long peerId = isBuyer ? conversation.getSellerId() : conversation.getBuyerId();
        vo.setPeerId(peerId);
        User peer = userMapper.selectById(peerId);
        if (peer != null) {
            vo.setPeerNickname(peer.getNickname());
            vo.setPeerAvatar(peer.getAvatar());
            vo.setPeerCampus(peer.getCampus());
        }

        Item item = itemMapper.selectById(conversation.getItemId());
        if (item != null) {
            vo.setItemTitle(item.getTitle());
            vo.setItemCover(item.getCover());
            vo.setItemPrice(item.getPrice() == null ? null : item.getPrice().toPlainString());
            vo.setItemStatus(item.getStatus());
        }
        return vo;
    }

    private String preview(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= PREVIEW_LENGTH ? content : content.substring(0, PREVIEW_LENGTH) + "...";
    }

    /** 后台概览：会话与消息总量 */
    public long totalConversations() {
        return conversationMapper.countAll();
    }

    public long totalMessages() {
        return messageMapper.countAll();
    }

    /** 清理某个商品的会话（商品删除时调用，未使用但保留给运维脚本） */
    @Transactional(rollbackFor = Exception.class)
    public void hideByItem(Long itemId) {
        conversationMapper.selectIdsByItemId(itemId).forEach(id -> {
            Conversation update = new Conversation();
            update.setConversationId(id);
            update.setDelFlag(2);
            conversationMapper.updateById(update);
        });
    }

    /** 会话是否属于该用户（供 Controller 复用） */
    public boolean isMember(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.selectAliveById(conversationId);
        return conversation != null
                && (userId.equals(conversation.getBuyerId()) || userId.equals(conversation.getSellerId()));
    }

    /** 统计某用户参与会话数 */
    public long countMine(Long userId) {
        Long count = conversationMapper.selectCount(Wrappers.<Conversation>lambdaQuery()
                .and(w -> w.eq(Conversation::getBuyerId, userId).or().eq(Conversation::getSellerId, userId)));
        return count == null ? 0L : count;
    }
}
