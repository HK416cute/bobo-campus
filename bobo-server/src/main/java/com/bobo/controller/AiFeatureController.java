package com.bobo.controller;

import com.bobo.ai.AiAvailability;
import com.bobo.ai.AiSettingsProvider;
import com.bobo.ai.AiTextRouter;
import com.bobo.ai.rag.KnowledgeRetriever;
import com.bobo.ai.spi.VisionInvoker;
import com.bobo.common.Result;
import com.bobo.domain.vo.AiUpgradeVos;
import com.bobo.security.Anonymous;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 技术升级能力总览：前端据此决定展示哪些入口、是否禁用「AI 估价」按钮。
 *
 * <p>匿名可读：状态不包含任何密钥或用户数据，首页/发布页在未登录时也能显示能力提示。
 * 具体调用仍需登录（由各接口的拦截器保证）。
 *
 * @author bobo
 */
@Tag(name = "20 AI 升级能力总览")
@RestController
@RequestMapping("/api/ai/features")
@RequiredArgsConstructor
public class AiFeatureController {

    private final AiAvailability availability;
    private final VisionInvoker visionInvoker;
    private final KnowledgeRetriever knowledgeRetriever;
    private final AiTextRouter textRouter;
    private final AiSettingsProvider settingsProvider;

    @Anonymous
    @Operation(summary = "智能定价 / 知识库问答 / 智能撮合 的可用状态")
    @GetMapping
    public Result<AiUpgradeVos.FeatureStatusVO> features() {
        AiUpgradeVos.FeatureStatusVO vo = new AiUpgradeVos.FeatureStatusVO();
        vo.setTextAvailable(availability.modelUsable());
        vo.setTextModel(availability.modelName());
        vo.setVisionAvailable(visionInvoker.available());
        vo.setVisionModel(visionInvoker.model());
        vo.setVisionReason(visionInvoker.available() ? null : visionInvoker.unavailableReason());

        AiUpgradeVos.KnowledgeStatsVO stats = knowledgeRetriever.stats();
        vo.setRagReady(knowledgeRetriever.ready());
        vo.setRagChunkCount(stats.getChunkCount());

        // 撮合即使没有模型也能用规则跑通，所以恒为可用；mode 说明当前是模型还是规则
        vo.setMatchAvailable(true);
        vo.setMatchMode(textRouter.mode());
        vo.setDailyQuota(settingsProvider.current().dailyQuota());
        vo.setUserQps(settingsProvider.current().userQps());
        return Result.ok(vo);
    }
}
