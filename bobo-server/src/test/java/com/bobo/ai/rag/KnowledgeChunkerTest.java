package com.bobo.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切片边界与长度约束。
 *
 * <p>切片器写错的典型症状是「答案刚好跨在两块之间，两边都缺半句」，
 * 或者「超长段落把一整块撑爆」。这两点都用测试钉住。
 *
 * @author bobo
 */
class KnowledgeChunkerTest {

    private final KnowledgeChunker chunker = new KnowledgeChunker();

    @Test
    @DisplayName("长文档切成多块，且每块不超过上限")
    void chunksRespectLimit() {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 80; i++) {
            content.append("第").append(i).append("条：买卖双方应在校内公共场所当面验货，")
                    .append("确认商品外观与功能无误后再完成交易。\n");
        }
        List<String> chunks = chunker.chunk(content.toString(), 200, 40);

        assertThat(chunks).hasSizeGreaterThan(3);
        for (String chunk : chunks) {
            assertThat(chunk).isNotBlank();
            // 允许一块由“重叠尾段 + 新单元”组成，因此上限是 maxChars + overlap
            assertThat(chunk.length()).isLessThanOrEqualTo(240);
        }
        assertThat(String.join("\n", chunks)).contains("第1条").contains("第80条");
    }

    @Test
    @DisplayName("短文档不切分，保持原文行结构")
    void shortContentStaysOneChunk() {
        String content = "一、交易规则\n1. 当面验货。\n2. 不要私下转账。";
        List<String> chunks = chunker.chunk(content);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("当面验货").contains("不要私下转账");
    }

    @Test
    @DisplayName("超长单行会被硬切，不会产生超过上限的块")
    void veryLongLineIsSplit() {
        String content = "啊".repeat(1500);
        List<String> chunks = chunker.chunk(content, 300, 50);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(350);
        }
    }

    @Test
    @DisplayName("空内容返回空列表")
    void blankContentReturnsEmpty() {
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("   ")).isEmpty();
    }
}
