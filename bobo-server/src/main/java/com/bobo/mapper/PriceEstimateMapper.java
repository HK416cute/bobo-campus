package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.PriceEstimate;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 定价评估 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface PriceEstimateMapper extends BaseMapper<PriceEstimate> {
}
