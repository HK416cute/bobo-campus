package com.bobo.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.Message;
import com.bobo.domain.query.ConversationQuery;
import com.bobo.domain.vo.ConversationVO;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.mapper.ConversationMapper;
import com.bobo.mapper.MessageMapper;
import com.bobo.security.AuthContext;
import com.bobo.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 私信接口。
 *
 * @author bobo
 */
@Tag(name = "06 私信")
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final ConversationService conversationService;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    @Operation(summary = "发起会话（从商品详情点「聊一聊」）")
    @PostMapping("/conversations")
    public Result<ConversationVO> open(@Valid @RequestBody Dtos.ConversationOpenDTO dto) {
        return Result.ok(conversationService.openOrCreate(dto));
    }

    @Operation(summary = "我的会话列表")
    @GetMapping("/conversations")
    public Result<PageResult<ConversationVO>> conversations(ConversationQuery query) {
        return Result.ok(conversationService.page(query));
    }

    @Operation(summary = "我参与的会话总数")
    @GetMapping("/conversations/count")
    public Result<Map<String, Object>> conversationCount() {
        Page<ConversationVO> page = new Page<>(1, 1);
        IPage<ConversationVO> result = conversationMapper.selectConversationPage(page,
                new ConversationQuery(), AuthContext.userId());
        return Result.ok(Map.of("count", result.getTotal()));
    }

    @Operation(summary = "进入会话（顺带把发给我的消息置为已读）")
    @GetMapping("/conversations/{conversationId}")
    public Result<ConversationVO> enter(@PathVariable Long conversationId) {
        return Result.ok(conversationService.enter(conversationId));
    }

    @Operation(summary = "会话消息分页（时间倒序，第一页是最新消息）")
    @GetMapping("/conversations/{conversationId}/messages")
    public Result<PageResult<SimpleVos.MessageVO>> messages(@PathVariable Long conversationId,
                                                            @RequestParam(defaultValue = "1") Integer pageNum,
                                                            @RequestParam(defaultValue = "20") Integer pageSize) {
        return Result.ok(conversationService.messages(conversationId, pageNum, pageSize));
    }

    @Operation(summary = "会话里发给我的未读消息（轮询红点用）")
    @GetMapping("/conversations/{conversationId}/unread")
    public Result<List<Message>> unreadMessages(@PathVariable Long conversationId) {
        Long userId = AuthContext.userId();
        conversationService.requireMember(conversationId, userId);
        return Result.ok(messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getToId, userId)
                .eq(Message::getIsRead, 0)
                .orderByAsc(Message::getMessageId)
                .last("LIMIT 20")));
    }

    @Operation(summary = "清空我在某个会话里的未读数")
    @PostMapping("/conversations/{conversationId}/read")
    public Result<Map<String, Object>> markRead(@PathVariable Long conversationId) {
        Long userId = AuthContext.userId();
        conversationService.requireMember(conversationId, userId);
        conversationMapper.clearUnread(conversationId, userId);
        messageMapper.markRead(conversationId, userId);
        return Result.ok(Map.of("cleared", true));
    }

    @Operation(summary = "发送消息")
    @PostMapping("/send")
    public Result<SimpleVos.MessageVO> send(@Valid @RequestBody Dtos.MessageDTO dto) {
        return Result.ok(conversationService.send(dto));
    }

    @Operation(summary = "未读消息总数")
    @GetMapping("/unread-count")
    public Result<Map<String, Object>> unreadCount() {
        return Result.ok(Map.of("count", conversationService.unreadCount()));
    }
}
