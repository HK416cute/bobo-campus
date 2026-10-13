package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.Notice;
import org.apache.ibatis.annotations.Mapper;

/**
 * 公告 / 通知 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface NoticeMapper extends BaseMapper<Notice> {
}
