package com.bobo.controller;

import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.query.ReportQuery;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.service.ReportService;
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
 * 举报接口（前台提交，管理端处理在 AdminReportController）。
 *
 * @author bobo
 */
@Tag(name = "11 举报")
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "提交举报")
    @PostMapping
    public Result<Map<String, Object>> submit(@Valid @RequestBody Dtos.ReportDTO dto) {
        Long reportId = reportService.submit(dto);
        return Result.ok("举报已提交，我们会尽快处理", Map.of("reportId", reportId));
    }

    @Operation(summary = "我提交的举报")
    @GetMapping("/my")
    public Result<PageResult<SimpleVos.ReportVO>> my(ReportQuery query) {
        return Result.ok(reportService.myReports(query));
    }
}
