package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.AiCallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 调用流水 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface AiCallLogMapper extends BaseMapper<AiCallLog> {

    /** 统计当日某用户调用次数，用于每日配额判定（按自然日、含失败与降级） */
    @Select("SELECT COUNT(*) FROM bobo_ai_call_log "
            + "WHERE user_id = #{userId} AND create_time >= CURDATE()")
    int countTodayByUser(@Param("userId") Long userId);

    /** 统计某场景累计估算花费（元），用于成本看板 */
    @Select("SELECT IFNULL(SUM(estimated_cost), 0) FROM bobo_ai_call_log WHERE scene = #{scene}")
    BigDecimal sumCostByScene(@Param("scene") String scene);

    /**
     * 按场景聚合调用指标：次数、成功/降级/失败、平均耗时、成本、token。
     * 用一条 GROUP BY 而不是把流水全捞进内存——流水表会随时间无限增长。
     */
    @Select("""
            SELECT scene                                                                   AS scene,
                   COUNT(*)                                                                AS calls,
                   SUM(CASE WHEN status = 'success'  THEN 1 ELSE 0 END)                    AS successCount,
                   SUM(CASE WHEN status = 'degraded' THEN 1 ELSE 0 END)                    AS degradedCount,
                   SUM(CASE WHEN status = 'failed'   THEN 1 ELSE 0 END)                    AS failedCount,
                   IFNULL(AVG(CASE WHEN status = 'success' THEN latency_ms END), 0)        AS avgLatencyMs,
                   IFNULL(SUM(estimated_cost), 0)                                          AS estimatedCost,
                   IFNULL(SUM(total_tokens), 0)                                            AS totalTokens
              FROM bobo_ai_call_log
             GROUP BY scene
             ORDER BY calls DESC
            """)
    List<com.bobo.domain.vo.AiCallLogStatsVO> selectSceneStats();

    /**
     * 成功调用的耗时样本，用于在 Java 里算分位数。
     * 用 LIMIT 兜住极端情况：分位数不需要全量样本，但也不能把整表捞出来。
     */
    @Select("""
            SELECT latency_ms FROM bobo_ai_call_log
             WHERE status = 'success' AND latency_ms IS NOT NULL
             ORDER BY log_id DESC
             LIMIT #{limit}
            """)
    List<Long> selectSuccessLatencies(@Param("limit") int limit);

    /** 全量汇总（次数 / 成本 / token），一次查询出全部聚合值 */
    @Select("""
            SELECT COUNT(*)                                              AS calls,
                   IFNULL(SUM(estimated_cost), 0)                        AS estimatedCost,
                   IFNULL(SUM(total_tokens), 0)                          AS totalTokens
              FROM bobo_ai_call_log
            """)
    java.util.Map<String, Object> selectOverall();
}
