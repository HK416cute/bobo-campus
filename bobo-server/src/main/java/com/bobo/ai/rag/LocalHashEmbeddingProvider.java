package com.bobo.ai.rag;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 本地哈希向量：字符 unigram + bigram（中文）与单词（英文）的 hashing trick。
 *
 * <p>实现细节与理由：
 * <ol>
 *   <li><b>为什么用 bigram</b>：中文没有空格，单字向量会把「退款」与「付款」当成高度相似；
 *       加入相邻二字组合后，「退款」「假货」「违禁品」这类检索词才有区分度。</li>
 *   <li><b>为什么用 feature hashing 而不是词表</b>：知识库是运行期可增删的，
 *       固定词表会随文档变化失效；哈希把任意新词映射到固定维度，重建索引即可。</li>
 *   <li><b>为什么加 sign</b>：哈希冲突时不同特征可能相互抵消而不是简单叠加，
 *       这是 hashing trick 的标准做法，比纯累加更稳。</li>
 *   <li><b>TF 用 1+log(tf)</b>：抑制高频字（「的」「了」）的影响，不需要额外维护 IDF。</li>
 *   <li><b>L2 归一化</b>：之后余弦相似度退化为点积，检索时少一次开方。</li>
 * </ol>
 *
 * <p>维度 512 是权衡：知识库切片在千级，512 维足够区分且全量检索很快；
 * 向量以 Base64 float32 存储，单条约 2.7KB，几十条切片完全可以接受。
 *
 * @author bobo
 */
@Component
public class LocalHashEmbeddingProvider implements EmbeddingProvider {

    private static final int DIMENSION = 512;
    private static final int FNV_OFFSET = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSION];
        if (text == null || text.isBlank()) {
            return vector;
        }
        Map<String, Integer> counts = features(normalize(text));
        if (counts.isEmpty()) {
            return vector;
        }
        double norm = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int hash = fnv1a(entry.getKey());
            int index = hash & (DIMENSION - 1);
            // 用高位决定符号：同一个 token 永远落在同一个方向，结果可复现
            float sign = ((hash >>> 16) & 1) == 0 ? 1f : -1f;
            float weight = (float) (1.0 + Math.log(entry.getValue()));
            vector[index] += sign * weight;
        }
        // 归一化必须按「累加后的向量」算范数：哈希冲突可能让多个特征落在同一维，
        // 用特征权重平方和会把向量错误地放大/缩小。
        for (float value : vector) {
            norm += (double) value * value;
        }
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < DIMENSION; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    /**
     * 抽取特征：
     * <ul>
     *   <li>连续中文/日文/韩文：单字 + 相邻二字；</li>
     *   <li>连续英文/数字：整词小写（再补 3-gram，缓解「refund/refunds」）；</li>
     *   <li>标点与空白作为分隔符丢弃。</li>
     * </ul>
     */
    private Map<String, Integer> features(String text) {
        Map<String, Integer> counts = new HashMap<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder word = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushWord(word, counts);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, counts);
                word.append(Character.toLowerCase(c));
            } else {
                flushCjk(cjk, counts);
                flushWord(word, counts);
            }
        }
        flushCjk(cjk, counts);
        flushWord(word, counts);
        return counts;
    }

    private void flushCjk(StringBuilder buffer, Map<String, Integer> counts) {
        if (buffer.length() == 0) {
            return;
        }
        String run = buffer.toString();
        for (int i = 0; i < run.length(); i++) {
            add(counts, "c:" + run.charAt(i));
            if (i + 1 < run.length()) {
                add(counts, "b:" + run.substring(i, i + 2));
            }
        }
        buffer.setLength(0);
    }

    private void flushWord(StringBuilder buffer, Map<String, Integer> counts) {
        if (buffer.length() == 0) {
            return;
        }
        String token = buffer.toString();
        add(counts, "w:" + token);
        if (token.length() >= 4) {
            for (int i = 0; i + 3 <= token.length(); i++) {
                add(counts, "t:" + token.substring(i, i + 3));
            }
        }
        buffer.setLength(0);
    }

    private void add(Map<String, Integer> counts, String token) {
        counts.merge(token, 1, Integer::sum);
    }

    private String normalize(String text) {
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private boolean isCjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private int fnv1a(String token) {
        int hash = FNV_OFFSET;
        for (byte b : token.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= FNV_PRIME;
        }
        // 防止负数在取模时出现负索引（这里用 & 掩码，但仍保证非负语义清晰）
        return hash & 0x7FFFFFFF;
    }

    @Override
    public String encode(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    @Override
    public float[] decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length % 4 != 0) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            float[] vector = new float[bytes.length / 4];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = buffer.getFloat();
            }
            return vector;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0;
        }
        int length = Math.min(a.length, b.length);
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < length; i++) {
            dot += a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
