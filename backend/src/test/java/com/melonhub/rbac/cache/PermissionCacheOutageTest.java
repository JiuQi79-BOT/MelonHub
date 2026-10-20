package com.melonhub.rbac.cache;

import static org.assertj.core.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PermissionCacheOutageTest {
    @Test void writesProceedAndLookupsFallBackToTheDatabaseWhileRedisIsDown() {
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build());
        factory.afterPropertiesSet();
        try {
            var cache = new PermissionCache(new StringRedisTemplate(factory), new SimpleMeterRegistry(), true, "outage-test:");
            assertThat(cache.context("missing").status()).isEqualTo(PermissionCache.Status.BYPASS);
            assertThat(cache.applicationKey("missing").status()).isEqualTo(PermissionCache.Status.BYPASS);
            TransactionSynchronizationManager.initSynchronization();
            try {
                assertThatCode(() -> { cache.touchUser(1); cache.touchApplication(2); cache.touchKeys(2); }).doesNotThrowAnyException();
                for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();
                for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
            assertThat(cache.health()).isEqualTo("DOWN");
            assertThatCode(cache::recover).doesNotThrowAnyException();
            assertThat(cache.context("missing").status()).isEqualTo(PermissionCache.Status.BYPASS);
        } finally {
            factory.destroy();
        }
    }

    @Test void disabledCacheNeverTouchesRedis() {
        var cache = new PermissionCache(new StringRedisTemplate(), new SimpleMeterRegistry(), false, "disabled-test:");
        assertThat(cache.health()).isEqualTo("DISABLED");
        assertThat(cache.context("x").status()).isEqualTo(PermissionCache.Status.BYPASS);
        assertThatCode(() -> cache.touchUser(1)).doesNotThrowAnyException();
    }
}
