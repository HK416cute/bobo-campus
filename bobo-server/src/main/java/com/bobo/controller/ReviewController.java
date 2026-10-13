package com.bobo.controller;

import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.query.ReviewQuery;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.security.Anonymous;
import com.bobo.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 评价接口（前台，管理端在 AdminReviewController）。
 *
 * @author bobo
 */
@Tag(name = "10 评价")
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "提交评价（订单完成后双方各评一次）")
    @PostMapping
    public Result<Map<String, Object>> submit(@Valid @RequestBody Dtos.ReviewDTO dto) {
        Long reviewId = reviewService.submit(dto);
        return Result.ok("评价成功，感谢你的反馈", Map.of("reviewId", reviewId));
    }

    @Anonymous
    @Operation(summary = "评价分页（公开，可按用户/商品/关键词筛）")
    @GetMapping
    public Result<PageResult<SimpleVos.ReviewVO>> page(ReviewQuery query) {
        return Result.ok(reviewService.page(query));
    }

    @Operation(summary = "我发出的评价")
    @GetMapping("/my")
    public Result<PageResult<SimpleVos.ReviewVO>> my(ReviewQuery query) {
        return Result.ok(reviewService.myReviews(query));
    }
}
