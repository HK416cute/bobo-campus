package com.bobo.ai;

import com.bobo.domain.entity.AiSuggestionFeedback;
import com.bobo.domain.vo.AiCallLogStatsVO;
import com.bobo.domain.vo.AiVos;
import com.bobo.mapper.AiCallLogMapper;
import com.bobo.mapper.AiSuggestionFeedbackMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 效果面板：把流水与反馈表算成可展示、可写进简历的指标。
 *
 * <p>这是"AI 功能做完没做完"的判据。没有面板，就只能说"我接了 AI"；
 * 有了它，才能说出「发布建议采纳率 78%」「成功率 96%、降级率 4%」「P95 2.4s」
 * 「平均每次发布 0.0021 元」——面试官能分辨这两种表述的差别。
 *
 * <p>两个指标的定义要说清楚，避免自欺：
 * <ul>
 *   <li><b>降级率</b> = degraded / total。它衡量"模型到底有没有在干活"。
 *       如果这个数字很高而你没注意，说明线上的 AI 其实一直是规则在跑——这是最容易自欺的地方；</li>
 *   <li><b>采纳率</b> = 用户未修改直接提交 / 全部建议。分子分母都来自
 *       {@code bobo_ai_suggestion_feedback}，不是估的。</li>
 * </ul>
 *
 * <p>分位数用最近邻法在 Java 侧算，只取最近 5000 条成功样本：
 * 分位数不需要全量数据，而 `GROUP BY` 里算中位数在 MySQL 上没有可移植写法。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMetricsService {

    private static final int LATENCY_SAMPLE_LIMIT = 5000;

    private final AiCallLogMapper callLogMapper;
    private final AiSuggestionFeedbackMapper feedbackMapper;

    public AiVos.AiMetricsVO metrics() {
        AiVos.AiMetricsVO vo = new AiVos.AiMetricsVO();

        List<AiCallLogStatsVO> sceneStats = safeSceneStats();
        long total = 0;
        long success = 0;
        long degraded = 0;
        long failed = 0;
        long totalTokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        List<AiVos.SceneMetricsVO> scenes = new ArrayList<>();
        for (AiCallLogStatsVO row : sceneStats) {
            long calls = nz(row.getCalls());
            long s = nz(row.getSuccessCount());
            long d = nz(row.getDegradedCount());
            long f = nz(row.getFailedCount());
            total += calls;
            success += s;
            degraded += d;
            failed += f;
            totalTokens += nz(row.getTotalTokens());
            BigDecimal rowCost = row.getEstimatedCost() == null ? BigDecimal.ZERO : row.getEstimatedCost();
            cost = cost.add(rowCost);

            AiVos.SceneMetricsVO scene = new AiVos.SceneMetricsVO();
            scene.setScene(row.getScene());
            scene.setCalls(calls);
            scene.setSuccessCount(s);
            scene.setDegradedCount(d);
            scene.setFailedCount(f);
            scene.setAvgLatencyMs(nz(row.getAvgLatencyMs()));
            scene.setEstimatedCost(rowCost);
            scenes.add(scene);
        }
        vo.setScenes(scenes);
        vo.setTotalCalls(total);
        vo.setSuccessCount(success);
        vo.setDegradedCount(degraded);
        vo.setFailedCount(failed);
        vo.setSuccessRate(rate(success, total));
        vo.setDegradeRate(rate(degraded, total));
        vo.setEstimatedCost(cost);
        vo.setTotalTokens(totalTokens);

        long[] percentiles = latencyPercentiles();
        vo.setP50LatencyMs(percentiles[0]);
        vo.setP95LatencyMs(percentiles[1]);
        vo.setP99LatencyMs(percentiles[2]);

        fillAcceptRate(vo);
        return vo;
    }

    /** 采纳率与降级状态 */
    private void fillAcceptRate(AiVos.AiMetricsVO vo) {
        try {
            long draftTotal = feedbackMapper.countByType(AiSuggestionFeedback.TYPE_PUBLISH_DRAFT);
            long draftAccepted = feedbackMapper.countAcceptedByType(AiSuggestionFeedback.TYPE_PUBLISH_DRAFT);
            vo.setPublishDraftTotal(draftTotal);
            vo.setPublishDraftAccepted(draftAccepted);
            vo.setPublishDraftAcceptRate(rate(draftAccepted, draftTotal));

            long searchTotal = feedbackMapper.countByType(AiSuggestionFeedback.TYPE_SEARCH_QUERY);
            long searchAccepted = feedbackMapper.countAcceptedByType(AiSuggestionFeedback.TYPE_SEARCH_QUERY);
            vo.setSearchQueryTotal(searchTotal);
            vo.setSearchQueryAccepted(searchAccepted);
            vo.setSearchQueryAcceptRate(rate(searchAccepted, searchTotal));
        } catch (Exception e) {
            log.warn("统计 AI 采纳率失败：{}", e.getMessage());
        }
    }

    /** 成功调用的耗时 P50/P95/P99（最近邻法） */
    private long[] latencyPercentiles() {
        try {
            List<Long> latencies = callLogMapper.selectSuccessLatencies(LATENCY_SAMPLE_LIMIT);
            if (latencies == null || latencies.isEmpty()) {
                return new long[] {0, 0, 0};
            }
            List<Long> sorted = new ArrayList<>(latencies);
            sorted.sort(Long::compareTo);
            return new long[] {
                    percentile(sorted, 0.50),
                    percentile(sorted, 0.95),
                    percentile(sorted, 0.99)
            };
        } catch (Exception e) {
            log.warn("统计 AI 耗时失败：{}", e.getMessage());
            return new long[] {0, 0, 0};
        }
    }

    private long percentile(List<Long> sorted, double ratio) {
        int index = (int) Math.round(ratio * (sorted.size() - 1));
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }

    private List<AiCallLogStatsVO> safeSceneStats() {
        try {
            List<AiCallLogStatsVO> rows = callLogMapper.selectSceneStats();
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            // 新表还没建（例如只升级了代码没执行 03-ai.sql）时不至于让后台报错
            log.warn("查询 AI 场景统计失败（是否已执行 mysql/init/03-ai.sql？）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 供后台概览拼接一句摘要 */
    public Map<String, Object> summary() {
        AiVos.AiMetricsVO metrics = metrics();
        return Map.of(
                "totalCalls", metrics.getTotalCalls(),
                "successRate", metrics.getSuccessRate(),
                "degradeRate", metrics.getDegradeRate(),
                "publishDraftAcceptRate", metrics.getPublishDraftAcceptRate(),
                "estimatedCost", metrics.getEstimatedCost());
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    /** 比率，保留 4 位小数，分母为 0 时返回 0 */
    private double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0d;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }
}
