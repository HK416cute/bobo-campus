package com.bobo.controller;

import com.bobo.ai.match.MatchService;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.vo.AiUpgradeVos;
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

/**
 * 模块一：多智能体撮合接口。
 *
 * <p>发起撮合是同步的（最多 3 轮 / 30 秒），返回完整协商记录；
 * 达成只是建议，买卖双方分别调用 {@code /confirm} 才生效，
 * 之后买家可在下单时带 {@code matchId} 使用撮合价。
 *
 * @author bobo
 */
@Tag(name = "19 AI 智能撮合")
@RestController
@RequestMapping("/api/ai/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;

    @Operation(summary = "发起智能撮合（买家）")
    @PostMapping
    public Result<AiUpgradeVos.MatchDetailVO> create(@Valid @RequestBody Dtos.MatchCreateDTO dto) {
        return Result.ok(matchService.create(dto));
    }

    @Operation(summary = "我的撮合列表：role=buyer / seller")
    @GetMapping
    public Result<List<AiUpgradeVos.MatchVO>> list(
            @RequestParam(defaultValue = "buyer") String role) {
        return Result.ok(matchService.list(role));
    }

    @Operation(summary = "撮合详情（含完整协商记录）")
    @GetMapping("/{matchId}")
    public Result<AiUpgradeVos.MatchDetailVO> detail(@PathVariable Long matchId) {
        return Result.ok(matchService.detail(matchId));
    }

    @Operation(summary = "确认/拒绝撮合建议（买卖双方各自确认）")
    @PostMapping("/{matchId}/confirm")
    public Result<AiUpgradeVos.MatchDetailVO> confirm(@PathVariable Long matchId,
                                                      @Valid @RequestBody Dtos.MatchConfirmDTO dto) {
        return Result.ok(matchService.confirm(matchId, dto));
    }

    @Operation(summary = "取消撮合")
    @PostMapping("/{matchId}/cancel")
    public Result<Void> cancel(@PathVariable Long matchId) {
        matchService.cancel(matchId);
        return Result.ok();
    }
}
