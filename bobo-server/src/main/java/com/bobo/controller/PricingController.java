package com.bobo.controller;

import com.bobo.ai.pricing.PricingService;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.vo.AiUpgradeVos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块二：AI 视觉定价与成色评估接口。
 *
 * <p>只返回「建议」，不修改商品；卖家在发布页点「采用建议」后，
 * 由 {@code ItemDTO.aiEstimateId} 带入发布/编辑请求，才真正写入商品字段。
 *
 * <p>登录态由 JwtInterceptor 统一校验（本类未标注 {@code @Anonymous}），
 * 估价记录归属于当前用户，接口不接收 userId。
 *
 * @author bobo
 */
@Tag(name = "17 AI 智能定价")
@RestController
@RequestMapping("/api/ai/pricing")
@RequiredArgsConstructor
public class PricingController {

    private final PricingService pricingService;

    @Operation(summary = "视觉定价与成色评估（图片 + 描述 + 平台真实价位）")
    @PostMapping("/estimate")
    public Result<AiUpgradeVos.PriceEstimateVO> estimate(
            @Valid @RequestBody Dtos.PriceEstimateDTO dto) {
        return Result.ok(pricingService.estimate(dto));
    }
}
