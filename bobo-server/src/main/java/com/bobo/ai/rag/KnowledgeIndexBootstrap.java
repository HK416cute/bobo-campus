package com.bobo.ai.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 知识库启动引导：首次启动（或重建后）自动建索引。
 *
 * <p>为什么放在启动事件里而不是 SQL 初始化脚本里：切片与向量必须由 Java 的
 * {@link EmbeddingProvider} 生成，SQL 算不出来。自动建索引让「执行完 06 脚本 →
 * 重启服务 → 问答可用」这条升级路径不需要任何手工步骤。
 *
 * <p>建索引失败只记 warn，不阻断启动：知识库是增强能力，不能成为启动依赖。
 *
 * @author bobo
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeIndexBootstrap {

    private final KnowledgeIndexService indexService;

    @EventListener(ApplicationReadyEvent.class)
    public void buildIfNeeded() {
        try {
            if (!indexService.needsIndex()) {
                return;
            }
            KnowledgeIndexService.IndexResult result = indexService.rebuild();
            log.info("知识库首次自动建索引完成：文档 {} 篇，切片 {} 条",
                    result.docCount(), result.chunkCount());
        } catch (Exception e) {
            log.warn("知识库自动建索引失败（不影响启动，可在后台手动重建）：{}", e.getMessage());
        }
    }
}
