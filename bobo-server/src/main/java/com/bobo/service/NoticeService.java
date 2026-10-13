package com.bobo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bobo.common.PageResult;
import com.bobo.common.exception.BizException;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.Notice;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.mapper.NoticeMapper;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 公告 / 站内通知服务。
 *
 * @author bobo
 */
@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeMapper noticeMapper;

    /** 我的通知：个人通知 + 全站公告 */
    public PageResult<SimpleVos.NoticeVO> page(String type, Integer isRead, long pageNum, long pageSize) {
        Long userId = AuthContext.userId();
        Page<Notice> page = new Page<>(pageNum < 1 ? 1 : pageNum, pageSize < 1 ? 10 : Math.min(pageSize, 100));
        IPage<Notice> result = noticeMapper.selectPage(page, Wrappers.<Notice>lambdaQuery()
                .and(w -> w.eq(Notice::getUserId, userId).or().eq(Notice::getUserId, Notice.USER_ALL))
                .eq(StringUtils.hasText(type), Notice::getType, type)
                .eq(isRead != null, Notice::getIsRead, isRead)
                .orderByDesc(Notice::getCreateTime));
        return PageResult.of(result, this::toVO);
    }

    /** 全站公告（首页展示，匿名可读） */
    public java.util.List<SimpleVos.NoticeVO> broadcast(int limit) {
        java.util.List<Notice> notices = noticeMapper.selectList(Wrappers.<Notice>lambdaQuery()
                .eq(Notice::getUserId, Notice.USER_ALL)
                .orderByDesc(Notice::getCreateTime)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 20)));
        return notices.stream().map(this::toVO).toList();
    }

    /** 未读数 */
    public long unreadCount() {
        Long userId = AuthContext.userId();
        Long count = noticeMapper.selectCount(Wrappers.<Notice>lambdaQuery()
                .eq(Notice::getIsRead, 0)
                .and(w -> w.eq(Notice::getUserId, userId).or().eq(Notice::getUserId, Notice.USER_ALL)));
        return count == null ? 0L : count;
    }

    /** 标记单条已读 */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long noticeId) {
        Notice notice = noticeMapper.selectById(noticeId);
        if (notice == null) {
            throw BizException.notFound("通知不存在");
        }
        Long userId = AuthContext.userId();
        if (notice.getUserId() != Notice.USER_ALL && !userId.equals(notice.getUserId())) {
            throw BizException.forbidden("只能操作自己的通知");
        }
        if (notice.getIsRead() != null && notice.getIsRead() == 1) {
            return;
        }
        Notice update = new Notice();
        update.setNoticeId(noticeId);
        update.setIsRead(1);
        noticeMapper.updateById(update);
    }

    /** 全部标记已读（含全站公告，便于清空红点） */
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead() {
        Long userId = AuthContext.userId();
        Notice update = new Notice();
        update.setIsRead(1);
        return noticeMapper.update(update, Wrappers.<Notice>lambdaUpdate()
                .eq(Notice::getIsRead, 0)
                .and(w -> w.eq(Notice::getUserId, userId).or().eq(Notice::getUserId, Notice.USER_ALL)));
    }

    // ------------------------------------------------------------------ 管理端

    /** 后台公告分页 */
    public PageResult<SimpleVos.NoticeVO> adminPage(String type, long pageNum, long pageSize) {
        Page<Notice> page = new Page<>(pageNum < 1 ? 1 : pageNum, pageSize < 1 ? 10 : Math.min(pageSize, 100));
        IPage<Notice> result = noticeMapper.selectPage(page, Wrappers.<Notice>lambdaQuery()
                .eq(StringUtils.hasText(type), Notice::getType, type)
                .orderByDesc(Notice::getCreateTime));
        return PageResult.of(result, this::toVO);
    }

    /** 发布公告 / 给指定用户发通知 */
    @Transactional(rollbackFor = Exception.class)
    public Long publish(Dtos.NoticeDTO dto) {
        Notice notice = new Notice();
        notice.setUserId(dto.userId() == null ? Notice.USER_ALL : dto.userId());
        notice.setTitle(dto.title().trim());
        notice.setContent(dto.content());
        notice.setType(StringUtils.hasText(dto.type()) ? dto.type() : Notice.TYPE_SYSTEM);
        notice.setIsRead(0);
        notice.setDelFlag(0);
        noticeMapper.insert(notice);
        return notice.getNoticeId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long noticeId) {
        if (noticeMapper.selectById(noticeId) == null) {
            throw BizException.notFound("公告不存在");
        }
        noticeMapper.deleteById(noticeId);
    }

    public long countAll() {
        Long count = noticeMapper.selectCount(Wrappers.<Notice>lambdaQuery());
        return count == null ? 0L : count;
    }

    private SimpleVos.NoticeVO toVO(Notice notice) {
        SimpleVos.NoticeVO vo = new SimpleVos.NoticeVO();
        vo.setNoticeId(notice.getNoticeId());
        vo.setUserId(notice.getUserId());
        vo.setTitle(notice.getTitle());
        vo.setContent(notice.getContent());
        vo.setType(notice.getType());
        vo.setBizId(notice.getBizId());
        vo.setIsRead(notice.getIsRead());
        vo.setCreateTime(notice.getCreateTime());
        return vo;
    }
}
