package com.melonhub.rbac.infrastructure.config;

import com.melonhub.rbac.infrastructure.identity.IdentityTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 统一身份认证的密码和 JWT 配置。
 */
@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {

    /**
     * 注册统一身份密码编码器。
     *
     * @return BCrypt 编码器
     */
    @Bean
    public PasswordEncoder identityPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * 注册 JWT 令牌服务。
     *
     * @param secret HMAC 密钥
     * @param issuer 签发者
     * @param expiresInSeconds 有效期
     * @return JWT 服务
     */
    @Bean
    public IdentityTokenService identityTokenService(
            @Value("${melonhub.identity.jwt-secret}") String secret,
            @Value("${melonhub.identity.issuer:https://identity.example.edu}") String issuer,
            @Value("${melonhub.identity.expires-seconds:7200}") long expiresInSeconds
    ) {
        return new IdentityTokenService(secret, issuer, expiresInSeconds);
    }
}
