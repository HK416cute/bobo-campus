package com.bobo.ai.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库切片器：把规则文档切成「适合检索与引用」的片段。
 *
 * <p>三个设计点：
 * <ol>
 *   <li><b>按语义单元切</b>：优先在行/句号/分号处断开，不把一个条目从中间切断。
 *       否则用户问「退款流程」，召回的片段可能只有半句话。</li>
 *   <li><b>chunk 大小 480 字</b>：中文 480 字约 300~400 token，3~5 个片段
 *       送给模型仍然很便宜；太小则上下文不足，太大则召回精度下降。</li>
 *   <li><b>80 字重叠</b>：相邻切片共享尾部内容，避免「答案刚好跨在边界上」时两边都缺。</li>
 * </ol>
 *
 * <p>本类是纯函数（无数据库、无模型），因此切片边界、最大长度、重叠量都能单测。
 *
 * @author bobo
 */
@Component
public class KnowledgeChunker {

    /** 默认切片长度（字符） */
    public static final int DEFAULT_MAX_CHARS = 480;
    /** 默认重叠长度（字符） */
    public static final int DEFAULT_OVERLAP = 80;

    public List<String> chunk(String content) {
        return chunk(content, DEFAULT_MAX_CHARS, DEFAULT_OVERLAP);
    }

    public List<String> chunk(String content, int maxChars, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }
        int limit = Math.max(80, maxChars);
        int shared = Math.max(0, Math.min(overlap, limit / 3));

        List<String> units = splitUnits(content, limit);
        List<String> current = new ArrayList<>();
        int currentLength = 0;
        // 当前块里是否已经有“本轮新增”的单元（而不是上一块的尾巴）。
        // 若只有尾巴且新单元放不下，就丢掉尾巴直接开新块——否则会产出一个
        // 只由重叠内容组成、与上一块几乎重复的切片。
        boolean fresh = true;

        for (String unit : units) {
            // 超长单元已在 splitUnits 里被硬切，这里 +1 是换行符
            if (!current.isEmpty() && currentLength + unit.length() + 1 > limit) {
                if (fresh) {
                    chunks.add(join(current));
                    current = tailOf(current, shared);
                    fresh = false;
                } else {
                    current.clear();
                }
                currentLength = lengthOf(current);
            }
            current.add(unit);
            currentLength += unit.length() + 1;
            fresh = true;
        }
        if (!current.isEmpty()) {
            String last = join(current);
            if (chunks.isEmpty() || !chunks.get(chunks.size() - 1).equals(last)) {
                chunks.add(last);
            }
        }
        return chunks;
    }

    /**
     * 切成语义单元：先按行，行太长再按句末标点，句子还太长就硬切。
     */
    private List<String> splitUnits(String content, int maxChars) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        List<String> units = new ArrayList<>();
        for (String rawLine : normalized.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() <= maxChars) {
                units.add(line);
                continue;
            }
            for (String sentence : line.split("(?<=[。！？；!?;])")) {
                String part = sentence.trim();
                if (part.isEmpty()) {
                    continue;
                }
                if (part.length() <= maxChars) {
                    units.add(part);
                } else {
                    for (int i = 0; i < part.length(); i += maxChars) {
                        units.add(part.substring(i, Math.min(part.length(), i + maxChars)));
                    }
                }
            }
        }
        return units;
    }

    /** 取上一块的尾部若干单元作为重叠，总长度不超过 shared */
    private List<String> tailOf(List<String> current, int shared) {
        List<String> tail = new ArrayList<>();
        int length = 0;
        for (int i = current.size() - 1; i >= 0; i--) {
            String unit = current.get(i);
            if (length + unit.length() > shared) {
                break;
            }
            tail.add(0, unit);
            length += unit.length() + 1;
        }
        return tail;
    }

    private int lengthOf(List<String> units) {
        int length = 0;
        for (String unit : units) {
            length += unit.length() + 1;
        }
        return length;
    }

    private String join(List<String> units) {
        return String.join("\n", units);
    }
}
