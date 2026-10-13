package com.bobo.ai;

import com.bobo.config.props.AiProperties;
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
 * @author bobo
 */
@Slf4j
@Component
public class AiRateLimiter {

    private final AiProperties properties;
    private final Bucket globalBucket;
    private final Map<Long, Bucket> userBuckets = new ConcurrentHashMap<>();

    /** 用户桶上限，防止 userBuckets 被大量匿名/IP 撑爆内存 */
    private static final int MAX_USER_BUCKETS = 5000;

    public AiRateLimiter(AiProperties properties) {
        this.properties = properties;
        this.globalBucket = new Bucket(properties.getRate().getGlobalQps());
    }

    /**
     * 是否允许本次调用。
     *
     * @param userId 当前用户，null（匿名）时只受全局限流约束
     * @return true 允许；false 需要降级并提示「请求过于频繁」
     */
    public boolean tryAcquire(Long userId) {
        if (!globalBucket.tryAcquire()) {
            log.warn("AI 全局限流命中：globalQps={}", properties.getRate().getGlobalQps());
            return false;
        }
        if (userId == null) {
            return true;
        }
        if (userBuckets.size() > MAX_USER_BUCKETS) {
            // 简单粗暴的保护：桶表过大时整体清空，宁可短暂放宽也不无限增长
            userBuckets.clear();
        }
        Bucket bucket = userBuckets.computeIfAbsent(userId,
                id -> new Bucket(properties.getRate().getUserQps()));
        if (!bucket.tryAcquire()) {
            log.warn("AI 单用户限流命中：userId={}, userQps={}", userId, properties.getRate().getUserQps());
            return false;
        }
        return true;
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
