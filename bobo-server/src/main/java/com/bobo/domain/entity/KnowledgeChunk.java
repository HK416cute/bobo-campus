package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * RAG 知识库切片及其向量。
 *
 * <p>{@code embedding} 存 float32 小端数组的 Base64 文本：
 * <ul>
 *   <li>不依赖 Chroma/Qdrant 等外部服务，单 Jar 部署即可跑（毕设场景更轻）；</li>
 *   <li>切片规模在千级以内，全量加载后内存里算余弦相似度足够快；</li>
 *   <li>存文本便于运维直接看维度与长度，排查“为什么召回为空”。</li>
 * </ul>
 *
 * @author bobo
 */
@Data
@TableName("bobo_knowledge_chunk")
public class KnowledgeChunk implements Serializable {

    @TableId(value = "chunk_id", type = IdType.AUTO)
    private Long chunkId;

    private Long docId;
    private Integer chunkNo;
    private String content;
    /** 向量（float32 Base64） */
    private String embedding;
    private Integer dim;
    private LocalDateTime createTime;
}
