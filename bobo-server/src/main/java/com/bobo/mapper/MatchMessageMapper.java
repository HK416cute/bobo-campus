package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.MatchMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 撮合协商消息 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface MatchMessageMapper extends BaseMapper<MatchMessage> {
}
