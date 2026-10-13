package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.AiModeration;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
/**
 * AI 内容审核 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface AiModerationMapper extends BaseMapper<AiModeration> {

    /**
     * 按内容摘要查最近一次审核结论，实现幂等与缓存：
     * 同一段内容不重复调用模型，也保证结论稳定。
     */
    @Select("SELECT * FROM bobo_ai_moderation WHERE content_hash = #{contentHash} "
            + "ORDER BY moderation_id DESC LIMIT 1")
    AiModeration selectLatestByHash(@Param("contentHash") String contentHash);

    /** 统计待审核商品里的高风险条数，供后台角标使用 */
    @Select("SELECT COUNT(*) FROM bobo_ai_moderation "
            + "WHERE target_type = 'item' AND risk_level >= 2")
    int countHighRiskItems();
}
