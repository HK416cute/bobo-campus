package com.bobo.ai.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bobo.domain.entity.KnowledgeChunk;
import com.bobo.domain.entity.KnowledgeDoc;
import com.bobo.mapper.KnowledgeChunkMapper;
import com.bobo.mapper.KnowledgeDocMapper;
import com.bobo.domain.vo.AiUpgradeVos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索：问题向量 vs 切片向量，取余弦相似度 Top-K。
 *
 * <p>为什么每次查询都从数据库加载切片，而不是在内存里建常驻索引：
 * <ul>
 *   <li>知识库规模是几十到几百条，全量加载 + 点积在毫秒级；</li>
 *   <li>运营在后台重建索引后立即生效，不需要处理缓存失效；</li>
 *   <li>少一个「索引与数据库不一致」的故障面。</li>
 * </ul>
 * 如果将来切片上万，再把这里换成「启动加载 + 重建后刷新」即可，调用方不变。
 *
 * @author bobo
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetriever {

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final EmbeddingProvider embeddingProvider;

    /** 检索结果：切片 + 所属文档 + 得分 */
    public record Retrieved(KnowledgeDoc doc, KnowledgeChunk chunk, double score) {
        /** 引用编号展示为 R1、R2… */
        public String ref(int index) {
            return "R" + (index + 1);
        }
    }

    /**
     * 检索与问题最相关的切片。
     *
     * @param question 用户问题
     * @param topK     最多返回条数（技术规格书建议 3~5）
     * @param minScore 低于该余弦相似度视为不相关，避免把无关片段塞给模型
     */
    public List<Retrieved> search(String question, int topK, double minScore) {
        List<KnowledgeDoc> docs = enabledDocs();
        if (docs.isEmpty()) {
            return List.of();
        }
        Map<Long, KnowledgeDoc> docMap = new HashMap<>();
        for (KnowledgeDoc doc : docs) {
            docMap.put(doc.getDocId(), doc);
        }

        List<KnowledgeChunk> chunks;
        try {
            chunks = chunkMapper.selectList(Wrappers.<KnowledgeChunk>lambdaQuery()
                    .in(KnowledgeChunk::getDocId, docMap.keySet()));
        } catch (Exception e) {
            // 未执行 06-ai-upgrade.sql 的旧库：不让问答接口 500，返回空并提示不可用
            log.warn("知识库切片表不可用（是否已执行 mysql/init/06-ai-upgrade.sql？）：{}", e.getMessage());
            return List.of();
        }
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        float[] query = embeddingProvider.embed(question);
        List<Retrieved> scored = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            KnowledgeDoc doc = docMap.get(chunk.getDocId());
            if (doc == null) {
                continue;
            }
            float[] vector = embeddingProvider.decode(chunk.getEmbedding());
            if (vector == null) {
                continue;
            }
            double score = embeddingProvider.cosine(query, vector);
            if (score >= minScore) {
                scored.add(new Retrieved(doc, chunk, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Retrieved::score).reversed()
                .thenComparing(retrieved -> retrieved.chunk().getChunkId()));
        if (scored.size() > topK) {
            return new ArrayList<>(scored.subList(0, topK));
        }
        return scored;
    }

    /** 启用中的文档；表不可用时返回空而不是抛异常 */
    public List<KnowledgeDoc> enabledDocs() {
        try {
            List<KnowledgeDoc> docs = docMapper.selectList(Wrappers.<KnowledgeDoc>lambdaQuery()
                    .eq(KnowledgeDoc::getStatus, KnowledgeDoc.STATUS_ENABLED)
                    .orderByAsc(KnowledgeDoc::getDocId));
            return docs == null ? List.of() : docs;
        } catch (Exception e) {
            log.debug("读取知识库文档失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 知识库是否已建索引（问答接口据此区分「未初始化」与「不在知识库范围」） */
    public boolean ready() {
        try {
            return chunkMapper.selectCount(null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 统计信息，后台页面展示 */
    public AiUpgradeVos.KnowledgeStatsVO stats() {
        AiUpgradeVos.KnowledgeStatsVO vo = new AiUpgradeVos.KnowledgeStatsVO();
        vo.setDim(embeddingProvider.dimension());
        try {
            List<KnowledgeDoc> docs = docMapper.selectList(Wrappers.<KnowledgeDoc>lambdaQuery()
                    .orderByAsc(KnowledgeDoc::getDocId));
            long chunks = chunkMapper.selectCount(null);
            vo.setDocCount(docs == null ? 0 : docs.size());
            vo.setChunkCount(chunks);
            if (docs != null) {
                for (KnowledgeDoc doc : docs) {
                    AiUpgradeVos.KnowledgeDocVO item = new AiUpgradeVos.KnowledgeDocVO();
                    item.setDocId(doc.getDocId());
                    item.setTitle(doc.getTitle());
                    item.setCategory(doc.getCategory());
                    item.setStatus(doc.getStatus());
                    item.setChunkCount(doc.getChunkCount());
                    item.setContentLength(doc.getContent() == null ? 0 : doc.getContent().length());
                    item.setUpdateTime(doc.getUpdateTime());
                    vo.getDocs().add(item);
                }
            }
        } catch (Exception e) {
            vo.setWarning("知识库表不可用（是否已执行 mysql/init/06-ai-upgrade.sql？）：" + e.getMessage());
        }
        return vo;
    }
}
