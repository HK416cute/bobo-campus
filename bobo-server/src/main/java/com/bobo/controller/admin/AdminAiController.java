package com.bobo.controller.admin;

import com.bobo.ai.AdminCopilotService;
import com.bobo.ai.AiMetricsService;
import com.bobo.ai.AiModerationService;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.vo.AiVos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 管理端接口：效果面板、内容审核、运营 Copilot。
 *
 * <p>鉴权说明：整个 {@code /api/admin/**} 前缀由 `JwtInterceptor` 统一做管理员闸门
 * （见 architecture.md 第三节），因此本类<b>不需要</b>再写角色校验——
 * 漏标注解也不会漏权限。
 *
 * <p>这里的每个接口都只读或只产出「建议」：<b>不提供任何让模型直接改动业务数据的入口</b>。
 * 审核结论由管理员在原有审核流程里决定是否采纳，避免模型幻觉触发真实业务动作。
 *
 * @author bobo
 */
@Tag(name = "16 管理端 AI")
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
public class AdminAiController {

    private final AiMetricsService metricsService;
    private final AiModerationService moderationService;
    private final AdminCopilotService copilotService;

    @Operation(summary = "AI 效果面板：调用量/成功率/降级率/耗时/成本/采纳率")
    @GetMapping("/metrics")
    public Result<AiVos.AiMetricsVO> metrics() {
        return Result.ok(metricsService.metrics());
    }

    @Operation(summary = "内容风险预判")
    @PostMapping("/moderate")
    public Result<AiVos.ModerationVO> moderate(@Valid @RequestBody Dtos.AiModerateDTO dto) {
        return Result.ok(moderationService.moderate(dto.targetType(), dto.content(), dto.targetId()));
    }

    @Operation(summary = "审核结论列表")
    @GetMapping("/moderation")
    public Result<List<AiVos.ModerationItemVO>> moderationList(
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Integer riskLevel) {
        return Result.ok(moderationService.list(targetType, riskLevel));
    }

    @Operation(summary = "高风险商品数量（后台角标）")
    @GetMapping("/moderation/high-risk-count")
    public Result<Integer> highRiskCount() {
        return Result.ok(moderationService.highRiskItemCount());
    }

    @Operation(summary = "运营周报（自然语言）")
    @GetMapping("/report")
    public Result<AiVos.AdminReportVO> report(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(copilotService.weeklyReport(days));
    }

    @Operation(summary = "运营数据只读问答")
    @PostMapping("/ask")
    public Result<AiVos.AdminReportVO> ask(@Valid @RequestBody Dtos.AiAskDTO dto) {
        return Result.ok(copilotService.ask(dto.question()));
    }
}
