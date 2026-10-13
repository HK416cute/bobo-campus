package com.bobo.controller.admin;

import com.bobo.common.Result;
import com.bobo.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端数据看板。
 *
 * @author bobo
 */
@Tag(name = "20 管理端-数据看板")
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final StatsService statsService;

    @Operation(summary = "数据概览卡片")
    @GetMapping
    public Result<Map<String, Object>> dashboard() {
        return Result.ok(statsService.dashboard());
    }

    @Operation(summary = "近 N 天订单与成交额趋势")
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(statsService.trend(days));
    }

    @Operation(summary = "商品状态分布")
    @GetMapping("/status")
    public Result<List<Map<String, Object>>> status() {
        return Result.ok(statsService.itemStatusDistribution());
    }

    @Operation(summary = "分类商品分布")
    @GetMapping("/category")
    public Result<List<Map<String, Object>>> category() {
        return Result.ok(statsService.categoryDistribution());
    }

    @Operation(summary = "待处理事项红点（举报数等）")
    @GetMapping("/pending")
    public Result<Map<String, Object>> pending() {
        return Result.ok(statsService.pending());
    }
}
