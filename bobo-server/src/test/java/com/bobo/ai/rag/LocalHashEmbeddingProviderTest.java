package com.bobo.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地哈希向量的确定性、可逆性与语义区分能力。
 *
 * <p>这组测试是 RAG 的底座：向量一旦不确定或编码不可逆，
 * 召回结果会变成“重启一次一个样”，线上根本无法排查。
 *
 * @author bobo
 */
class LocalHashEmbeddingProviderTest {

    private final LocalHashEmbeddingProvider provider = new LocalHashEmbeddingProvider();

    @Test
    @DisplayName("同一段文本的向量完全确定，且余弦为 1")
    void deterministicAndSelfSimilar() {
        float[] first = provider.embed("买到假货怎么办？可以申请退款吗");
        float[] second = provider.embed("买到假货怎么办？可以申请退款吗");

        assertThat(first).hasSize(512);
        assertThat(first).containsExactly(second);
        assertThat(provider.cosine(first, second)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("语义相关的文本相似度明显高于无关文本")
    void relatedTextScoresHigher() {
        float[] query = provider.embed("买到假货怎么退款");
        float[] related = provider.embed("买家收到假货，可以在订单页申请退款，卖家同意后退款。");
        float[] unrelated = provider.embed("宿舍大功率电器需要符合学校用电规定。");

        double relatedScore = provider.cosine(query, related);
        double unrelatedScore = provider.cosine(query, unrelated);

        assertThat(relatedScore).isGreaterThan(0.15);
        assertThat(relatedScore).isGreaterThan(unrelatedScore + 0.1);
    }

    @Test
    @DisplayName("编码后可无损解码")
    void encodeDecodeRoundTrip() {
        float[] vector = provider.embed("违禁品清单：管制刀具、仿真枪、毒品");
        String encoded = provider.encode(vector);
        float[] decoded = provider.decode(encoded);

        assertThat(encoded).isNotBlank();
        assertThat(decoded).hasSize(vector.length);
        for (int i = 0; i < vector.length; i++) {
            assertThat(decoded[i]).isCloseTo(vector[i], org.assertj.core.data.Offset.offset(0.000001f));
        }
    }

    @Test
    @DisplayName("空文本返回零向量，余弦为 0 而不是 NaN")
    void blankTextIsSafe() {
        float[] empty = provider.embed("  ");
        for (float value : empty) {
            assertThat(value).isZero();
        }
        assertThat(provider.cosine(empty, provider.embed("退款"))).isZero();
        assertThat(provider.decode("not-base64!!")).isNull();
    }
}
