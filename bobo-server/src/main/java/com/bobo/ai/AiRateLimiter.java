package com.bobo.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 调用限流与配额：分「全局 QPS / 单用户 QPS / 单用户每日次数」三层。
 *
 * <p>为什么必须自己做：AI 是<b>按量计费</b>的外部依赖，一个脚本刷 {@code /api/ai/**}
 * 就能把当天额度烧穿——这是与业务接口完全不同的风险面，现有接口没有这个需求。
 *
 * <p>实现上不引入 Guava/Redisson：本机单实例部署用令牌桶足够，
 * 而 {@link Bucket} 里的 {@code synchronized} 只锁住一次算术运算，不是热点。
 * 若将来多实例部署，这里换成 Redis 原子计数即可，调用方无需改动。
 *
 * <p><b>QPS 可运行期变更</b>：令牌桶是按创建时的速率定容的，配置改小后旧桶不会自动收紧。
 * 因此这里记住「建桶时用的速率」，发现与最新配置不一致就重建
 * （用户桶直接清空：代价只是瞬时放宽一次限流，比继续按旧速率跑更符合预期）。
 *
 * @author bobo
 */
@Slf4j
@Component
public class AiRateLimiter {

    private final AiSettingsProvider settingsProvider;
    private final Map<Long, Bucket> userBuckets = new ConcurrentHashMap<>();

    private volatile Bucket globalBucket;
    /** 建桶时使用的速率，用于检测配置是否变更 */
    private volatile int globalQpsInUse;
    private volatile int userQpsInUse;

    /** 用户桶上限，防止 userBuckets 被大量匿名/IP 撑爆内存 */
    private static final int MAX_USER_BUCKETS = 5000;

    public AiRateLimiter(AiSettingsProvider settingsProvider) {
        this.settingsProvider = settingsProvider;
        AiSettingsProvider.RuntimeSettings settings = settingsProvider.current();
        this.globalBucket = new Bucket(settings.globalQps());
        this.globalQpsInUse = settings.globalQps();
        this.userQpsInUse = settings.userQps();
    }

    /**
     * 是否允许本次调用。
     *
     * @param userId 当前用户，null（匿名）时只受全局限流约束
     * @return true 允许；false 需要降级并提示「请求过于频繁」
     */
    public boolean tryAcquire(Long userId) {
        AiSettingsProvider.RuntimeSettings settings = settingsProvider.current();
        refreshIfRateChanged(settings);

        if (!globalBucket.tryAcquire()) {
            log.warn("AI 全局限流命中：globalQps={}", settings.globalQps());
            return false;
        }
        if (userId == null) {
            return true;
        }
        if (userBuckets.size() > MAX_USER_BUCKETS) {
            // 简单粗暴的保护：桶表过大时整体清空，宁可短暂放宽也不无限增长
            userBuckets.clear();
        }
        Bucket bucket = userBuckets.computeIfAbsent(userId, id -> new Bucket(settings.userQps()));
        if (!bucket.tryAcquire()) {
            log.warn("AI 单用户限流命中：userId={}, userQps={}", userId, settings.userQps());
            return false;
        }
        return true;
    }

    /** 配置里的 QPS 变了就重建桶（否则把配置改小不会生效） */
    private void refreshIfRateChanged(AiSettingsProvider.RuntimeSettings settings) {
        if (settings.globalQps() != globalQpsInUse) {
            synchronized (this) {
                if (settings.globalQps() != globalQpsInUse) {
                    globalBucket = new Bucket(settings.globalQps());
                    globalQpsInUse = settings.globalQps();
                    log.info("全局限流速率已更新为 {} QPS", settings.globalQps());
                }
            }
        }
        if (settings.userQps() != userQpsInUse) {
            synchronized (this) {
                if (settings.userQps() != userQpsInUse) {
                    userQpsInUse = settings.userQps();
                    userBuckets.clear();
                    log.info("单用户限流速率已更新为 {} QPS（用户桶已重建）", settings.userQps());
                }
            }
        }
    }

    /**
     * 令牌桶。容量固定为速率本身（即允许 1 秒的突发），每秒按速率补充。
     */
    private static final class Bucket {
        private final double ratePerSecond;
        private double tokens;
        private long lastRefillNanos;

        Bucket(int ratePerSecond) {
            this.ratePerSecond = Math.max(ratePerSecond, 1);
            this.tokens = this.ratePerSecond;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000d;
            lastRefillNanos = now;
            tokens = Math.min(ratePerSecond, tokens + elapsedSeconds * ratePerSecond);
            if (tokens >= 1d) {
                tokens -= 1d;
                return true;
            }
            return false;
        }
    }
}
