package com.melonhub.client;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/** 配置 melonhub.client.api-key 后自动装配客户端、授权门面与权限拦截器。 */
@AutoConfiguration
@ConditionalOnProperty("melonhub.client.api-key")
@EnableConfigurationProperties(MelonHubClientProperties.class)
public class MelonHubAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MelonHubClient melonHubClient(MelonHubClientProperties properties) {
        return MelonHubClient.builder(properties.getBaseUrl(), properties.getApiKey())
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .requestTimeout(Duration.ofMillis(properties.getRequestTimeoutMillis()))
                .maxRetries(properties.getMaxRetries())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public MelonHubAuthorizer melonHubAuthorizer(MelonHubClient client) {
        return new MelonHubAuthorizer(client);
    }

    @Configuration(proxyBeanMethods=false)
    @ConditionalOnClass({WebMvcConfigurer.class, jakarta.servlet.http.HttpServletRequest.class})
    static class MvcConfiguration {
        @Bean
        @ConditionalOnMissingBean
        MelonHubPermissionInterceptor melonHubPermissionInterceptor(MelonHubAuthorizer authorizer,
                                                                    MelonHubClientProperties properties) {
            return new MelonHubPermissionInterceptor(authorizer, properties.getUserHeader());
        }

        @Bean
        @ConditionalOnMissingBean
        MelonHubWebMvcConfigurer melonHubWebMvcConfigurer(MelonHubPermissionInterceptor interceptor) {
            return new MelonHubWebMvcConfigurer(interceptor);
        }
    }
}
