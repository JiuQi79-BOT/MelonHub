package com.melonhub.rbac.security;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Platform-wide fixed-window counters kept in Redis so every instance enforces the same budget. When Redis fails,
 * this instance uses its own token bucket and only retries Redis after a short pause, so an outage does not add
 * a Redis timeout to every request.
 */
@Component
public class GlobalRateLimiter {
    private static final DefaultRedisScript<Long> HIT = new DefaultRedisScript<>("""
        local v = redis.call('INCR', KEYS[1])
        if v == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
        return v
        """, Long.class);
    private static final long BREAKER_MILLIS = 5_000;

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final String prefix;
    private final Map<String,RateLimiter> fallbacks = new ConcurrentHashMap<>();
    private volatile long openUntil;

    public GlobalRateLimiter(StringRedisTemplate redis,
                             @Value("${melonhub.cache.enabled:true}") boolean cacheEnabled,
                             @Value("${melonhub.rate-limit.global:true}") boolean global,
                             @Value("${melonhub.cache.prefix:melonhub:}") String prefix) {
        this.redis = redis;
        this.enabled = cacheEnabled && global;
        this.prefix = prefix;
    }

    /** Fixed window of {@code windowSeconds}; {@code limit <= 0} disables limiting for this bucket. */
    public boolean tryAcquire(String name, String id, long limit, int windowSeconds) {
        if (limit <= 0) return true;
        long now = System.currentTimeMillis();
        if (!enabled || now < openUntil) return fallback(name, limit, windowSeconds).tryAcquire(id);
        try {
            long window = now / 1000 / windowSeconds;
            Long hits = redis.execute(HIT, List.of(prefix + "rl:" + name + ":" + id + ":" + window), String.valueOf(windowSeconds + 1));
            return hits == null || hits <= limit;
        } catch (RuntimeException e) {
            openUntil = System.currentTimeMillis() + BREAKER_MILLIS;
            return fallback(name, limit, windowSeconds).tryAcquire(id);
        }
    }

    private RateLimiter fallback(String name, long limit, int windowSeconds) {
        double perSecond = limit / (double) windowSeconds;
        return fallbacks.computeIfAbsent(name + ':' + limit + ':' + windowSeconds, k -> new RateLimiter(perSecond, Math.max(1, limit)));
    }
}
