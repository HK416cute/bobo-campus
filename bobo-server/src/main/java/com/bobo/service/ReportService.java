package com.bobo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bobo.common.PageResult;
import com.bobo.common.exception.BizException;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.Item;
import com.bobo.domain.entity.Report;
import com.bobo.domain.entity.Review;
import com.bobo.domain.entity.User;
import com.bobo.domain.query.ReportQuery;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.mapper.ItemMapper;
import com.bobo.mapper.ReportMapper;
import com.bobo.mapper.ReviewMapper;
import com.bobo.mapper.UserMapper;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 举报服务。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final Set<String> TARGET_TYPES = Set.of(
            Report.TARGET_ITEM, Report.TARGET_USER, Report.TARGET_REVIEW);

    private final ReportMapper reportMapper;
    private final ItemMapper itemMapper;
    private final UserMapper userMapper;
    private final ReviewMapper reviewMapper;
    private final ItemService itemService;
    private final NotificationService notificationService;

    /** 提交举报 */
    @Transactional(rollbackFor = Exception.class)
    public Long submit(Dtos.ReportDTO dto) {
        if (!TARGET_TYPES.contains(dto.targetType())) {
            throw new BizException("举报对象类型不正确");
        }
        Long userId = AuthContext.userId();
        if (Report.TARGET_ITEM.equals(dto.targetType())) {
            Item item = itemMapper.selectById(dto.targetId());
            if (item == null) {
                throw BizException.notFound("被举报的商品不存在");
            }
            if (userId.equals(item.getSellerId())) {
                throw new BizException("不能举报自己的商品");
            }
        } else if (Report.TARGET_USER.equals(dto.targetType())) {
            if (userId.equals(dto.targetId())) {
                throw new BizException("不能举报自己");
            }
            if (userMapper.selectById(dto.targetId()) == null) {
                throw BizException.notFound("被举报的用户不存在");
            }
        } else {
            if (reviewMapper.selectById(dto.targetId()) == null) {
                throw BizException.notFound("被举报的评价不存在");
            }
        }

        Long duplicated = reportMapper.selectCount(Wrappers.<Report>lambdaQuery()
                .eq(Report::getUserId, userId)
                .eq(Report::getTargetType, dto.targetType())
                .eq(Report::getTargetId, dto.targetId())
                .eq(Report::getStatus, Report.STATUS_PENDING));
        if (duplicated != null && duplicated > 0) {
            throw new BizException("你已经举报过该内容，我们正在处理中");
        }

        Report report = new Report();
        report.setUserId(userId);
        report.setTargetType(dto.targetType());
        report.setTargetId(dto.targetId());
        report.setReason(dto.reason().trim());
        report.setDetail(dto.detail());
        report.setStatus(Report.STATUS_PENDING);
        report.setDelFlag(0);
        reportMapper.insert(report);
        return report.getReportId();
    }

    /** 我提交的举报 */
    public PageResult<SimpleVos.ReportVO> myReports(ReportQuery query) {
        Page<SimpleVos.ReportVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<SimpleVos.ReportVO> result = reportMapper.selectReportPage(page, query);
        // 过滤出自己提交的（复用同一条 SQL，避免再写一份关联查询）
        List<SimpleVos.ReportVO> mine = result.getRecords().stream()
                .filter(vo -> AuthContext.userId().equals(vo.getUserId()))
                .toList();
        return PageResult.of(mine, mine.size(), query.safePageNum(), query.safePageSize());
    }

    // ------------------------------------------------------------------ 管理端

    public PageResult<SimpleVos.ReportVO> adminPage(ReportQuery query) {
        Page<SimpleVos.ReportVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<SimpleVos.ReportVO> result = reportMapper.selectReportPage(page, query);
        return PageResult.of(result);
    }

    /** 处理举报：可选同时下架被举报商品、扣信用分 */
    @Transactional(rollbackFor = Exception.class)
    public void handle(Long reportId, Dtos.ReportHandleDTO dto) {
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            throw BizException.notFound("举报记录不存在");
        }
        if (report.getStatus() != null && report.getStatus() != Report.STATUS_PENDING) {
            throw new BizException("该举报已处理过了");
        }

        Report update = new Report();
        update.setReportId(reportId);
        update.setStatus(dto.handled() ? Report.STATUS_HANDLED : Report.STATUS_REJECTED);
        update.setHandleRemark(dto.remark());
        update.setHandleBy(AuthContext.userId());
        update.setHandleTime(LocalDateTime.now());
        reportMapper.updateById(update);

        if (dto.handled()) {
            if (dto.offShelfItem() != null && dto.offShelfItem()
                    && Report.TARGET_ITEM.equals(report.getTargetType())) {
                itemService.offShelfByAdmin(report.getTargetId(), "被举报下架：" + report.getReason());
            }
            if (Report.TARGET_REVIEW.equals(report.getTargetType())) {
                reviewMapper.updateStatus(report.getTargetId(), Review.STATUS_HIDDEN);
            }
            if (Report.TARGET_USER.equals(report.getTargetType())) {
                // 被举报成立时扣 5 分信用分，由运营决定是否另行封禁
                userMapper.changeCreditScore(report.getTargetId(), -5);
            }
        }

        notificationService.notify(dto.handled() ? "你的举报已处理" : "你的举报未通过",
                "你举报的内容（" + report.getReason() + "）处理结果："
                        + (dto.handled() ? "已处理" : "未认定违规")
                        + (dto.remark() == null ? "" : "，" + dto.remark()),
                com.bobo.domain.entity.Notice.TYPE_SYSTEM, reportId, report.getUserId());
    }

    public long countPending() {
        return reportMapper.countPending();
    }

    /** 举报人昵称（详情展示用，预留） */
    public String reporterNickname(Long userId) {
        User user = userMapper.selectById(userId);
        return user == null ? null : user.getNickname();
    }
}
