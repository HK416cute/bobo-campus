package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * RAG 知识库文档（交易规则 / 违禁品清单 / 纠纷处理流程）。
 *
 * <p>文档是运营可维护的数据，切片与向量由 {@code KnowledgeIndexService} 在启动或
 * 后台点「重建索引」时生成。这样改完规则正文只需重建索引，不必手工算向量。
 *
 * @author bobo
 */
@Data
@TableName("bobo_knowledge_doc")
public class KnowledgeDoc implements Serializable {

    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED = 1;

    @TableId(value = "doc_id", type = IdType.AUTO)
    private Long docId;

    private String title;
    /** 交易规则 / 违禁品 / 纠纷处理 */
    private String category;
    private String content;
    private String source;
    private Integer status;
    /** 最近一次重建索引生成的切片数 */
    private Integer chunkCount;
    private LocalDateTime updateTime;
    private LocalDateTime createTime;
}
