package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分类 Mapper。分类树 / 数量统计见 {@link ItemMapper#countByCategory()}。
 *
 * @author bobo
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
