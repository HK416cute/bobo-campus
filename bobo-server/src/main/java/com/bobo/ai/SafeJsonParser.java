package com.bobo.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 宽容 JSON 解析器：把模型返回的文本尽力还原成对象。
 *
 * <p>为什么不能直接用 {@code objectMapper.readValue(raw, X.class)}：
 * 即使提示词里写了「只输出 JSON」，模型仍会时不时加上 Markdown 代码块围栏、
 * 前置一句「好的，以下是结果」或尾随解释。若直接抛异常，用户拿到的是空白表单，
 * 而其实结果就在那段文本里——这是「模型不稳定」最常见的伪故障。
 *
 * <p>因此这里做三级容错：<b>去围栏 → 截取首个完整 JSON 对象 → 反序列化</b>，
 * 三步都失败才返回 {@code null}（由 {@link AiExecutor} 触发规则降级）。
 *
 * <p>注意：<b>不在这里做业务校验</b>。字段是否合法由各场景的解析函数判断，
 * 因为「JSON 能解析」和「内容可用」是两件事——没标题的草稿能解析但不能用。
 *
 * @author bobo
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SafeJsonParser {

    private final ObjectMapper objectMapper;

    /**
     * 解析并映射，失败返回 {@code null}。
     *
     * @param raw    模型原始输出
     * @param mapper 拿到 {@link JsonNode} 后构造目标对象；返回 null 表示业务校验不通过
     */
    public <T> T parse(String raw, Function<JsonNode, T> mapper) {
        String json = extractJson(raw);
        if (json == null) {
            log.debug("模型输出中未找到 JSON 对象，长度={}", raw == null ? 0 : raw.length());
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.isNull() || node.isMissingNode()) {
                return null;
            }
            return mapper.apply(node);
        } catch (Exception e) {
            log.debug("模型输出 JSON 反序列化失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 从任意文本里抽出 JSON 文本片段。
     *
     * @return JSON 文本；找不到平衡的 {'...'} 片段时返回 null
     */
    public String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }

        // 1) 去掉 ```json ... ``` / ``` ... ``` 围栏
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1).trim();
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence).trim();
            }
        }

        // 2) 已是纯 JSON
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }

        // 3) 截取第一个花括号开始、括号平衡的片段（跳过字符串字面量内的括号）
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        // 括号不平衡（多半是被 max_tokens 截断），返回 null 交给降级
        return null;
    }
}
