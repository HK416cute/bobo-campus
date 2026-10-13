package com.bobo.controller.admin;

import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.query.ReviewQuery;
import com.bobo.domain.query.ReportQuery;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.service.ReportService;
import com.bobo.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端内容治理：评价审核与举报处理。
 *
 * @author bobo
 */
@Tag(name = "24 管理端-评价与举报")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminContentController {

    private final ReviewService reviewService;
    private final ReportService reportService;

    // ------------------------------------------------------------------ 评价

    @Operation(summary = "评价分页")
    @GetMapping("/reviews")
    public Result<PageResult<SimpleVos.ReviewVO>> reviews(ReviewQuery query) {
        return Result.ok(reviewService.page(query));
    }

    @Operation(summary = "隐藏评价")
    @PutMapping("/reviews/{reviewId}/hide")
    public Result<Void> hideReview(@PathVariable Long reviewId) {
        reviewService.changeStatus(reviewId, com.bobo.domain.entity.Review.STATUS_HIDDEN);
        return Result.ok("已隐藏", null);
    }

    @Operation(summary = "恢复显示评价")
    @PutMapping("/reviews/{reviewId}/show")
    public Result<Void> showReview(@PathVariable Long reviewId) {
        reviewService.changeStatus(reviewId, com.bobo.domain.entity.Review.STATUS_VISIBLE);
        return Result.ok("已恢复显示", null);
    }

    @Operation(summary = "删除评价")
    @DeleteMapping("/reviews/{reviewId}")
    public Result<Void> deleteReview(@PathVariable Long reviewId) {
        reviewService.delete(reviewId);
        return Result.ok("已删除", null);
    }

    // ------------------------------------------------------------------ 举报

    @Operation(summary = "举报分页（待处理优先）")
    @GetMapping("/reports")
    public Result<PageResult<SimpleVos.ReportVO>> reports(ReportQuery query) {
        return Result.ok(reportService.adminPage(query));
    }

    @Operation(summary = "处理举报（可同时下架商品）")
    @PutMapping("/reports/{reportId}/handle")
    public Result<Void> handle(@PathVariable Long reportId, @Valid @RequestBody Dtos.ReportHandleDTO dto) {
        reportService.handle(reportId, dto);
        return Result.ok(dto.handled() ? "已处理" : "已驳回", null);
    }
}
