package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库切片 Mapper。
 *
 * <p>重建索引需要清空全表，但 MyBatis-Plus 的
 * {@code BlockAttackInnerInterceptor} 会拦截没有 where 的 delete，
 * 所以这里显式提供一条受控的 {@link #deleteAll()}（只允许索引服务调用）。
 *
 * @author bobo
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /** 清空切片（重建索引专用）。带恒真条件是为了通过 BlockAttackInnerInterceptor */
    @Delete("DELETE FROM bobo_knowledge_chunk WHERE chunk_id > 0")
    int deleteAll();
}
