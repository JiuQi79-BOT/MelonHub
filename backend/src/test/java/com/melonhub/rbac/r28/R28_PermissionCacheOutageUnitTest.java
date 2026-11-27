package com.melonhub.rbac.r28;

import static org.assertj.core.api.Assertions.*;

import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 对应需求：R28（见 doc/requirements.md）。 */
class R28_PermissionCacheOutageUnitTest {

    // =========================================================================
    // 统一测试参数区（不可达 Redis 地址/端口/超时、前缀与预期降级状态）
    // =========================================================================
    private static final String UNREACHABLE_HOST = "127.0.0.1";
    private static final int UNREACHABLE_PORT = 1;
    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(200);
    private static final String PREFIX_OUTAGE = "outage-test:";
    private static final String PREFIX_DISABLED = "disabled-test:";
    private static final String MISSING_KEY = "missing";
    private static final long SAMPLE_USER_ID = 1L;
    private static final long SAMPLE_APP_ID = 2L;
    private static final long SAMPLE_DEPT_ID = 3L;
    private static final String HEALTH_DOWN = "DOWN";
    private static final String HEALTH_DISABLED = "DISABLED";

    @DisplayName("R28 Redis 宕机时写操作继续且查询自动熔断回退数据库")
    @Test void writesProceedAndLookupsFallBackToTheDatabaseWhileRedisIsDown() {
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(UNREACHABLE_HOST, UNREACHABLE_PORT),
                LettuceClientConfiguration.builder().commandTimeout(COMMAND_TIMEOUT).build());
        factory.afterPropertiesSet();
        try {
            var cache = new PermissionCache(new StringRedisTemplate(factory), new SimpleMeterRegistry(), true, PREFIX_OUTAGE);
            assertThat(cache.context(MISSING_KEY).status()).isEqualTo(PermissionCache.Status.BYPASS);
            assertThat(cache.applicationKey(MISSING_KEY).status()).isEqualTo(PermissionCache.Status.BYPASS);
            TransactionSynchronizationManager.initSynchronization();
            try {
                assertThatCode(() -> {
                    cache.touchUser(SAMPLE_USER_ID);
                    cache.touchApplication(SAMPLE_APP_ID);
                    cache.touchDomain(SAMPLE_DEPT_ID, SAMPLE_APP_ID);
                }).doesNotThrowAnyException();
                for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();
                for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
            assertThat(cache.health()).isEqualTo(HEALTH_DOWN);
            assertThatCode(cache::recover).doesNotThrowAnyException();
            assertThat(cache.context(MISSING_KEY).status()).isEqualTo(PermissionCache.Status.BYPASS);
        } finally {
            factory.destroy();
        }
    }

    @DisplayName("R28 CACHE_ENABLED=false 关闭缓存时零触碰 Redis")
    @Test void disabledCacheNeverTouchesRedis() {
        var cache = new PermissionCache(new StringRedisTemplate(), new SimpleMeterRegistry(), false, PREFIX_DISABLED);
        assertThat(cache.health()).isEqualTo(HEALTH_DISABLED);
        assertThat(cache.context(MISSING_KEY).status()).isEqualTo(PermissionCache.Status.BYPASS);
        assertThatCode(() -> cache.touchUser(SAMPLE_USER_ID)).doesNotThrowAnyException();
    }
}
