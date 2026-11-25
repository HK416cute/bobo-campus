package com.bobo.ai.rag;

import com.bobo.ai.AiOutcome;
import com.bobo.ai.AiTextRouter;
import com.bobo.ai.SafeJsonParser;
import com.bobo.domain.entity.AiCallLog;
import com.bobo.domain.vo.AiUpgradeVos;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 知识库问答：检索片段 → 约束模型只基于片段回答 → 返回可核验引用。
 *
 * <p>防幻觉不是靠一句「不要编造」的口号，而是三道硬约束：
 * <ol>
 *   <li><b>没有命中就拒答</b>：相似度低于阈值时直接返回「不在知识库范围」，
 *       根本不调模型——没有上下文时模型最可能开始编；</li>
 *   <li><b>必须带引用</b>：模型输出里的 citation 编号会被逐条校验，
 *       不存在的编号丢弃；一条有效引用都没有就判为不可用、走规则摘录；</li>
 *   <li><b>降级返回原文</b>：模型不可用时返回片段原文摘录，而不是让用户看到空白，
 *       原文至少是真的。</li>
 * </ol>
 *
 * <p>成本上，命中后才调用模型，且只送 Top-5 片段；无命中时零 token。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    /** 召回条数：技术规格书建议 3~5 条 */
    private static final int TOP_K = 5;
    /** 最低相似度：低于它视为知识库不覆盖该问题 */
    private static final double MIN_SCORE = 0.10;
    /** 送给模型的单片段最大字符数，避免超长片段挤占上下文 */
    private static final int SNIPPET_LIMIT = 420;

    private static final String SYSTEM_PROMPT = """
            你是 bobo 校园二手交易平台的知识库问答助手。你只能依据用户消息中给出的「资料片段」回答，
            不得使用片段之外的知识，也不得编造规则、金额、时限或流程。

            回答规则：
            1. 如果片段不足以回答该问题，answer 只写「不在知识库范围」，citations 给空数组；
            2. 回答要具体、可执行，每个结论后用 [R1]、[R2] 这样的编号标注依据；
            3. 只引用真实存在的片段编号，不得引用不存在的编号；
            4. 不要输出 Markdown 代码块，不要输出 JSON 之外的任何文字。

            输出字段：
            - answer：面向学生的中文回答，200 字以内，必要时分点；
            - citations：实际引用到的片段编号数字数组，如 [1, 3]。
            """;

    private final AiTextRouter textRouter;
    private final SafeJsonParser jsonParser;
    private final KnowledgeRetriever retriever;

    /** 问答入口 */
    public AiUpgradeVos.RagAnswerVO ask(String question) {
        long start = System.currentTimeMillis();
        String query = question == null ? "" : question.trim();

        if (!retriever.ready()) {
            AiUpgradeVos.RagAnswerVO vo = new AiUpgradeVos.RagAnswerVO();
            vo.setAnswer("知识库尚未初始化（文档已存在但还没有索引）。请在管理后台「AI 运营 → 知识库」点一次「重建索引」，或重启服务。");
            vo.setInScope(false);
            vo.setSource("rule");
            vo.setDegradeNote("知识库未建索引");
            vo.setLatencyMs(System.currentTimeMillis() - start);
            return vo;
        }

        List<KnowledgeRetriever.Retrieved> hits = retriever.search(query, TOP_K, MIN_SCORE);
        if (hits.isEmpty()) {
            AiUpgradeVos.RagAnswerVO vo = new AiUpgradeVos.RagAnswerVO();
            vo.setAnswer("不在知识库范围。当前知识库覆盖交易规则、违禁品清单与纠纷处理流程，可以换个问法再试。");
            vo.setInScope(false);
            vo.setHitCount(0);
            vo.setSource("rule");
            vo.setDegradeNote("未检索到足够相关的片段，已拒答以避免编造");
            vo.setLatencyMs(System.currentTimeMillis() - start);
            return vo;
        }

        String userPrompt = buildPrompt(query, hits);
        AiOutcome<AiUpgradeVos.RagAnswerVO> outcome = textRouter.execute(
                AiCallLog.SCENE_RAG_QA,
                SYSTEM_PROMPT,
                userPrompt,
                raw -> parse(raw, hits),
                () -> fallback(hits));

        AiUpgradeVos.RagAnswerVO vo = outcome.value();
        vo.setHitCount(hits.size());
        vo.setSource(outcome.degraded() ? "rule" : "model");
        vo.setDegradeNote(outcome.degradeNote());
        vo.setLogId(outcome.logId());
        vo.setLatencyMs(System.currentTimeMillis() - start);
        return vo;
    }

    // ------------------------------------------------------------------ 提示词

    private String buildPrompt(String question, List<KnowledgeRetriever.Retrieved> hits) {
        StringBuilder sb = new StringBuilder("资料片段（按相关性排序）：\n\n");
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeRetriever.Retrieved hit = hits.get(i);
            sb.append("[R").append(i + 1).append("] 《").append(hit.doc().getTitle())
                    .append("》（").append(hit.doc().getCategory()).append("）\n")
                    .append(clip(hit.chunk().getContent(), SNIPPET_LIMIT))
                    .append("\n\n");
        }
        sb.append("用户问题：").append(question);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 解析与校验

    /**
     * 解析模型 JSON，并逐条校验引用编号。
     * 返回 null 表示结果不可用（无 answer、或引用了不存在的片段）。
     */
    private AiUpgradeVos.RagAnswerVO parse(String raw, List<KnowledgeRetriever.Retrieved> hits) {
        return jsonParser.parse(raw, node -> {
            String answer = text(node, "answer");
            if (!StringUtils.hasText(answer)) {
                return null;
            }
            answer = answer.trim();
            if (answer.length() > 2000) {
                answer = answer.substring(0, 2000);
            }

            Set<Integer> refs = parseRefs(node.get("citations"));
            // 模型常常只在正文里写 [R1] 而忘了 citations 数组，从正文补一次
            refs.addAll(refsFromAnswer(answer, hits.size()));

            boolean refusal = answer.contains("不在知识库范围");
            if (refs.isEmpty() && !refusal) {
                // 没有有效引用 = 无法核验，判为不可用，走规则摘录
                return null;
            }

            AiUpgradeVos.RagAnswerVO vo = new AiUpgradeVos.RagAnswerVO();
            vo.setAnswer(answer);
            vo.setInScope(!refusal);
            vo.setCitations(toCitations(refs, hits));
            if (vo.getCitations().isEmpty() && !refusal) {
                return null;
            }
            // 模型给了引用但正文没写编号时补一句，保证用户能对上
            if (!refusal && !answer.contains("[R")) {
                StringBuilder refsText = new StringBuilder();
                for (AiUpgradeVos.CitationVO citation : vo.getCitations()) {
                    refsText.append('[').append(citation.getRef()).append(']');
                }
                vo.setAnswer(answer + " 依据：" + refsText);
            }
            return vo;
        });
    }

    private Set<Integer> parseRefs(JsonNode node) {
        Set<Integer> refs = new LinkedHashSet<>();
        if (node == null || node.isNull()) {
            return refs;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                Integer value = refValue(item);
                if (value != null) {
                    refs.add(value);
                }
            }
        } else {
            Integer value = refValue(node);
            if (value != null) {
                refs.add(value);
            }
        }
        return refs;
    }

    private Integer refValue(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        String text = item.asText().trim();
        if (text.startsWith("R") || text.startsWith("r")) {
            text = text.substring(1);
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从正文提取 [R1] 形式的引用 */
    private Set<Integer> refsFromAnswer(String answer, int max) {
        Set<Integer> refs = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\[R(\\d{1,2})]").matcher(answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= 1 && index <= max) {
                refs.add(index);
            }
        }
        return refs;
    }

    private List<AiUpgradeVos.CitationVO> toCitations(Set<Integer> refs,
                                                      List<KnowledgeRetriever.Retrieved> hits) {
        List<AiUpgradeVos.CitationVO> citations = new ArrayList<>();
        for (Integer index : refs) {
            if (index == null || index < 1 || index > hits.size()) {
                continue; // 模型编出来的编号直接丢弃
            }
            KnowledgeRetriever.Retrieved hit = hits.get(index - 1);
            AiUpgradeVos.CitationVO citation = new AiUpgradeVos.CitationVO();
            citation.setRef("R" + index);
            citation.setDocId(hit.doc().getDocId());
            citation.setDocTitle(hit.doc().getTitle());
            citation.setCategory(hit.doc().getCategory());
            citation.setChunkNo(hit.chunk().getChunkNo());
            citation.setSnippet(clip(hit.chunk().getContent(), 180));
            citation.setScore(Math.round(hit.score() * 1000) / 1000.0);
            citations.add(citation);
        }
        return citations;
    }

    /**
     * 规则兜底：直接摘录最相关的片段原文。
     * 宁可给用户三段原文，也不让模型在没有可用输出时硬编一段话。
     */
    private AiUpgradeVos.RagAnswerVO fallback(List<KnowledgeRetriever.Retrieved> hits) {
        AiUpgradeVos.RagAnswerVO vo = new AiUpgradeVos.RagAnswerVO();
        vo.setInScope(true);
        Set<Integer> refs = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder("模型暂时不可用，以下是从平台规则中检索到的原文，供你直接参考：\n");
        int limit = Math.min(3, hits.size());
        for (int i = 0; i < limit; i++) {
            KnowledgeRetriever.Retrieved hit = hits.get(i);
            refs.add(i + 1);
            sb.append('[').append('R').append(i + 1).append("] ")
                    .append(clip(hit.chunk().getContent(), 260)).append('\n');
        }
        vo.setAnswer(sb.toString().trim());
        vo.setCitations(toCitations(refs, hits));
        return vo;
    }

    // ------------------------------------------------------------------ 小工具

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
