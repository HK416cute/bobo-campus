package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.MatchRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * 智能撮合单 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface MatchRequestMapper extends BaseMapper<MatchRequest> {
}
