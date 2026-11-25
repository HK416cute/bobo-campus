package com.bobo.ai.rag;

/**
 * 文本向量化抽象。
 *
 * <p><b>为什么不是 DeepSeek 的 embedding 接口</b>：
 * DeepSeek 当前提供的多模态/对话能力不包含 embedding 端点（见 docs/ai.md 的取舍说明）。
 * 技术规格书要求 RAG，但没规定必须用哪家的向量——本项目选择<b>本地可解释的哈希向量</b>：
 * <ul>
 *   <li>零外部依赖：不用再起 Chroma/Qdrant/Redis Stack，单 Jar 部署即可；</li>
 *   <li>中文场景够用：字符 unigram + bigram 对「假货」「退款」「违禁品」这类
 *       短查询的召回效果稳定，且完全确定、可测试；</li>
 *   <li>可替换：真要上语义向量，只需实现本接口（例如接 Voyage / BGE），
 *       检索与问答链路不用改。</li>
 * </ul>
 *
 * <p>局限也要说清楚：它靠字面重合，不理解同义词（「退款」vs「退钱」）。
 * 因此 RAG 的回答提示词仍要求模型只基于片段、并输出引用编号，用可核验性换召回的上限。
 *
 * @author bobo
 */
public interface EmbeddingProvider {

    /** 向量维度 */
    int dimension();

    /** 文本 → 已 L2 归一化的向量；空文本返回全零向量 */
    float[] embed(String text);

    /** 向量 → 可落库的字符串（Base64 float32） */
    String encode(float[] vector);

    /** 字符串 → 向量；损坏或为空时返回 null */
    float[] decode(String encoded);

    /** 余弦相似度；任一为零向量时返回 0 */
    double cosine(float[] a, float[] b);
}
