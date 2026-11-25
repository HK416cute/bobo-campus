package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.KnowledgeDoc;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库文档 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDoc> {
}
