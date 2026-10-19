package com.melonhub.rbac.security;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class GlobalRateLimiterTest {
    @Test void redisFailureFallsBackToTheLocalBucketWithoutRetryingRedisOnEveryRequest() {
        var calls = new AtomicInteger();
        var redis = new StringRedisTemplate() {
            @Override public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                calls.incrementAndGet();
                throw new RedisConnectionFailureException("down");
            }
        };
        var limiter = new GlobalRateLimiter(redis, true, true, "rl-test:");
        assertThat(limiter.tryAcquire("login", "10.0.0.1", 2, 60)).isTrue();
        assertThat(limiter.tryAcquire("login", "10.0.0.1", 2, 60)).isTrue();
        assertThat(limiter.tryAcquire("login", "10.0.0.1", 2, 60)).isFalse();
        assertThat(calls).hasValue(1);
    }
}
