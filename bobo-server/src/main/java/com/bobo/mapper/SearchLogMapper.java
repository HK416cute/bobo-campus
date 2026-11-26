package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.SearchLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 搜索关键词日志 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface SearchLogMapper extends BaseMapper<SearchLog> {

    /**
     * 统计最近 N 天的热门关键词。
     *
     * <p>只取关键词与次数，不返回用户信息；按次数降序，次数相同按词排序，
     * 保证首页刷新时顺序稳定。
     *
     * @return 每行包含 {@code keyword} 与 {@code cnt}
     */
    @Select("""
            SELECT keyword AS keyword, COUNT(*) AS cnt
              FROM bobo_search_log
             WHERE create_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY)
               AND keyword IS NOT NULL AND keyword <> ''
             GROUP BY keyword
             ORDER BY cnt DESC, keyword ASC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> selectHotKeywords(@Param("days") int days,
                                                @Param("limit") int limit);
}
