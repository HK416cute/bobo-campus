package com.bobo.controller;

import com.bobo.ai.AiAvailability;
import com.bobo.ai.AiFeedbackRecorder;
import com.bobo.ai.AiPublishService;
import com.bobo.ai.AiSearchService;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.vo.AiVos;
import com.bobo.security.Anonymous;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 能力接口（前台：发布助手 + 检索解析 + 采纳反馈）。
 *
 * <p>三个共同约定：
 * <ol>
 *   <li><b>一律返回 200</b>，与全站 `Result` 契约一致；AI 不可用时通过 {@code source=rule}
 *       与 {@code degradeNote} 表达，而不是抛错让前端白屏；</li>
 *   <li><b>不自动执行业务动作</b>：发布助手只回填表单，是否提交由用户决定；</li>
 *   <li><b>不接收 userId</b>：身份一律从 `AuthContext` 取，模型与前端都无法指定"我是谁"。</li>
 * </ol>
 *
 * @author bobo
 */
@Tag(name = "15 AI 能力")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiPublishService publishService;
    private final AiSearchService searchService;
    private final AiFeedbackRecorder feedbackRecorder;
    private final AiAvailability availability;

    /** AI 是否可用与降级原因，前端据此决定是否展示「AI 帮我填」按钮 */
    @Anonymous
    @Operation(summary = "AI 能力状态")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", !availability.degraded());
        data.put("degraded", availability.degraded());
        data.put("model", availability.modelName());
        data.put("reason", availability.unavailableReason());
        return Result.ok(data);
    }

    @Operation(summary = "AI 发布助手：草稿补全为结构化建议")
    @PostMapping("/publish/draft")
    public Result<AiVos.ItemDraftVO> publishDraft(@Valid @RequestBody Dtos.AiDraftDTO dto) {
        return Result.ok(publishService.generateDraft(dto.text(), dto.categoryId()));
    }

    @Anonymous
    @Operation(summary = "检索意图解析：自然语言 → 结构化检索槽位")
    @PostMapping("/search/parse")
    public Result<AiVos.SearchSlotsVO> parseSearch(@Valid @RequestBody Dtos.AiSearchParseDTO dto) {
        return Result.ok(searchService.parse(dto.query()));
    }

    @Operation(summary = "AI 建议采纳反馈")
    @PostMapping("/feedback")
    public Result<Void> feedback(@Valid @RequestBody Dtos.AiFeedbackDTO dto) {
        if (!feedbackRecorder.isKnownType(dto.suggestionType())) {
            return Result.fail(Result.CODE_BAD_REQUEST, "未知的建议类型");
        }
        feedbackRecorder.record(dto.suggestionType(), Boolean.TRUE.equals(dto.accepted()),
                dto.suggestedValue(), dto.finalValue(), dto.targetId(), dto.logId());
        return Result.ok();
    }
}
