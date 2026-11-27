package com.melonhub.rbac.r29;

import static org.assertj.core.api.Assertions.assertThat;

import com.melonhub.rbac.infrastructure.security.GlobalRateLimiter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** 对应需求：R29（见 doc/requirements.md）。 */
class R29_GlobalRateLimiterUnitTest {

    // =========================================================================
    // 统一测试参数区（限流前缀、限流桶名称、来源 IP、窗口阈值与预期 Redis 调用次数）
    // =========================================================================
    private static final String REDIS_PREFIX = "rl-test:";
    private static final String BUCKET_LOGIN = "login";
    private static final String CLIENT_IP = "10.0.0.1";
    private static final long LIMIT_PER_WINDOW = 2L;
    private static final int WINDOW_SECONDS = 60;
    private static final int EXPECTED_REDIS_CALLS_DURING_OUTAGE = 1;

    @DisplayName("R29 Redis 限流故障后 5 秒熔断退回本地令牌桶不逐请求重试")
    @Test void redisFailureFallsBackToTheLocalBucketWithoutRetryingRedisOnEveryRequest() {
        var calls = new AtomicInteger();
        var redis = new StringRedisTemplate() {
            @Override public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                calls.incrementAndGet();
                throw new RedisConnectionFailureException("down");
            }
        };
        var limiter = new GlobalRateLimiter(redis, true, true, REDIS_PREFIX);
        assertThat(limiter.tryAcquire(BUCKET_LOGIN, CLIENT_IP, LIMIT_PER_WINDOW, WINDOW_SECONDS)).isTrue();
        assertThat(limiter.tryAcquire(BUCKET_LOGIN, CLIENT_IP, LIMIT_PER_WINDOW, WINDOW_SECONDS)).isTrue();
        assertThat(limiter.tryAcquire(BUCKET_LOGIN, CLIENT_IP, LIMIT_PER_WINDOW, WINDOW_SECONDS)).isFalse();
        assertThat(calls).hasValue(EXPECTED_REDIS_CALLS_DURING_OUTAGE);
    }
}
