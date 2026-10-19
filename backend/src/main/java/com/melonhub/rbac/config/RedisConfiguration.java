package com.melonhub.rbac.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RedisConfiguration {
    /** Reject commands while disconnected instead of queueing them, so a Redis outage degrades to MySQL immediately. */
    @Bean LettuceClientConfigurationBuilderCustomizer failFastRedis() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(300)))
                .build());
    }
}
