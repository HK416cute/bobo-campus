package com.bobo.service;

import com.bobo.domain.entity.Notice;
import com.bobo.mapper.NoticeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 站内通知服务。
 * <p>
 * 被多个 Service 复用（审核结果、订单状态、退款结果），因此单独成类，
 * 避免每个业务 Service 都写一遍 insert。写入失败不影响主流程（通知不是关键路径）。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NoticeMapper noticeMapper;

    /**
     * 给指定用户发通知。
     *
     * @param userId 接收人；null 或 0 表示全站公告
     */
    public void notify(String title, String content, String type, Long bizId, Long userId) {
        try {
            Notice notice = new Notice();
            notice.setUserId(userId == null ? Notice.USER_ALL : userId);
            notice.setTitle(truncate(title, 100));
            notice.setContent(truncate(content, 500));
            notice.setType(StringUtils.hasText(type) ? type : Notice.TYPE_SYSTEM);
            notice.setBizId(bizId);
            notice.setIsRead(0);
            notice.setDelFlag(0);
            noticeMapper.insert(notice);
        } catch (Exception e) {
            // 通知失败只记日志：不能让"发通知"把下单、审核这些主流程拖回滚
            log.warn("站内通知写入失败: {}", e.getMessage());
        }
    }

    /** 全站公告 */
    public void broadcast(String title, String content, String type) {
        notify(title, content, type, null, Notice.USER_ALL);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
