package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.AiSuggestionFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 建议反馈 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface AiSuggestionFeedbackMapper extends BaseMapper<AiSuggestionFeedback> {

    /** 某类建议的总条数（分母） */
    @Select("SELECT COUNT(*) FROM bobo_ai_suggestion_feedback WHERE suggestion_type = #{type}")
    int countByType(@Param("type") String type);

    /** 某类建议中被采纳的条数（分子）——采纳率 = 本值 / countByType */
    @Select("SELECT COUNT(*) FROM bobo_ai_suggestion_feedback "
            + "WHERE suggestion_type = #{type} AND accepted = 1")
    int countAcceptedByType(@Param("type") String type);
}
