package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.AiSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 运行配置 Mapper。
 *
 * <p>不需要自定义 SQL：配置只有一行，用 {@code selectById(SINGLETON_ID)}
 * 与 {@code insert/updateById} 即可，交给 MyBatis-Plus 维护更少出错。
 *
 * @author bobo
 */
@Mapper
public interface AiSettingMapper extends BaseMapper<AiSetting> {
}
