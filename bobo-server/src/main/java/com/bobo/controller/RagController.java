package com.bobo.controller;

import com.bobo.ai.rag.RagService;
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
 * 模块三：RAG 知识库问答接口。
 *
 * <p>响应里带可核验的引用（文档、切片编号、原文摘要），前端逐条展示；
 * 无命中时返回「不在知识库范围」而不是让模型自由发挥。
 *
 * @author bobo
 */
@Tag(name = "18 AI 知识库问答")
@RestController
@RequestMapping("/api/ai/qa")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    @Operation(summary = "基于知识库的规则问答（带引用）")
    @PostMapping
    public Result<AiUpgradeVos.RagAnswerVO> ask(@Valid @RequestBody Dtos.AiQaDTO dto) {
        return Result.ok(ragService.ask(dto.question()));
    }
}
