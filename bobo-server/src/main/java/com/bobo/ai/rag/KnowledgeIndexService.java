package com.bobo.ai.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bobo.domain.entity.KnowledgeChunk;
import com.bobo.domain.entity.KnowledgeDoc;
import com.bobo.mapper.KnowledgeChunkMapper;
import com.bobo.mapper.KnowledgeDocMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识库索引服务：把文档切成片段、向量化并落库。
 *
 * <p><b>为什么在服务端建索引，而不是在 SQL 里预置向量</b>：
 * 向量由 Java 的 {@link EmbeddingProvider} 生成，SQL 里没法算；
 * 而且运营改完规则正文后，只需点一次「重建索引」，不用手工维护向量。
 *
 * <p>重建策略：先清空切片表，再逐文档切片、向量化、插入，最后回写
 * {@code bobo_knowledge_doc.chunk_count}。整个过程在一个事务里，
 * 失败不会留下「一半新一半旧」的索引。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIndexService {

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeChunker chunker;
    private final EmbeddingProvider embeddingProvider;

    /** 重建结果 */
    public record IndexResult(int docCount, int chunkCount) {
    }

    /**
     * 全量重建索引。
     *
     * <p>由后台「重建索引」按钮或首次启动时的 Bootstrap 调用；
     * 必须从外部调用才能让 {@code @Transactional} 代理生效（同类自调用会绕过）。
     */
    @Transactional(rollbackFor = Exception.class)
    public IndexResult rebuild() {
        List<KnowledgeDoc> docs = docMapper.selectList(Wrappers.<KnowledgeDoc>lambdaQuery()
                .eq(KnowledgeDoc::getStatus, KnowledgeDoc.STATUS_ENABLED)
                .orderByAsc(KnowledgeDoc::getDocId));
        if (docs == null || docs.isEmpty()) {
            return new IndexResult(0, 0);
        }

        chunkMapper.deleteAll();
        int totalChunks = 0;
        for (KnowledgeDoc doc : docs) {
            List<String> pieces = chunker.chunk(doc.getContent());
            int chunkNo = 0;
            for (String piece : pieces) {
                // 向量文本带上标题与类目：用户问“违禁品”时，标题里的词也能参与匹配
                String embedText = doc.getTitle() + "\n" + doc.getCategory() + "\n" + piece;
                float[] vector = embeddingProvider.embed(embedText);

                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setDocId(doc.getDocId());
                chunk.setChunkNo(++chunkNo);
                chunk.setContent(piece);
                chunk.setEmbedding(embeddingProvider.encode(vector));
                chunk.setDim(vector.length);
                chunkMapper.insert(chunk);
                totalChunks++;
            }
            KnowledgeDoc update = new KnowledgeDoc();
            update.setDocId(doc.getDocId());
            update.setChunkCount(chunkNo);
            docMapper.updateById(update);
        }
        log.info("知识库索引重建完成：文档 {} 篇，切片 {} 条，维度 {}",
                docs.size(), totalChunks, embeddingProvider.dimension());
        return new IndexResult(docs.size(), totalChunks);
    }

    /**
     * 是否需要建索引：有启用文档但一条切片都没有。
     * 表不存在时返回 false（由 Bootstrap 记 warn，不影响启动）。
     */
    public boolean needsIndex() {
        try {
            Long docs = docMapper.selectCount(Wrappers.<KnowledgeDoc>lambdaQuery()
                    .eq(KnowledgeDoc::getStatus, KnowledgeDoc.STATUS_ENABLED));
            if (docs == null || docs == 0) {
                return false;
            }
            Long chunks = chunkMapper.selectCount(null);
            return chunks == null || chunks == 0;
        } catch (Exception e) {
            log.warn("知识库表不可用（是否已执行 mysql/init/06-ai-upgrade.sql？）：{}", e.getMessage());
            return false;
        }
    }
}
