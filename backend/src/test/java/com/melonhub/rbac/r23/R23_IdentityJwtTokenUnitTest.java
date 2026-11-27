package com.melonhub.rbac.r23;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.melonhub.rbac.infrastructure.identity.IdentityTokenService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 统一身份 JWT 的签发与校验行为测试。
 * 对应需求：R23（见 doc/requirements.md）。
 */
class R23_IdentityJwtTokenUnitTest {

    // =========================================================================
    // 统一测试参数区（JWT 签名密钥、签发方、有效期、主体与合法/非法受众系统）
    // =========================================================================
    private static final String JWT_SECRET = "test-secret-test-secret-test-secret";
    private static final String JWT_ISSUER = "https://identity.test";
    private static final long JWT_TTL_SECONDS = 7200L;
    private static final String SUBJECT = "sso-u1001";
    private static final String TARGET_SYSTEM = "course-project-management";
    private static final String OTHER_SYSTEM = "other-system";
    private static final String TAMPER_SUFFIX = "x";

    /**
     * 令牌应包含目标系统、主体和有效期，并能被同一服务校验。
     */
    @Test
    @DisplayName("R23 统一身份 HS256 JWT 正常签发并校验主体、目标系统与有效期")
    void RIdentityTokenCanBeIssuedAndVerified() {
        IdentityTokenService service = new IdentityTokenService(JWT_SECRET, JWT_ISSUER, JWT_TTL_SECONDS);

        String token = service.issue(SUBJECT, List.of(TARGET_SYSTEM), Instant.now());
        IdentityTokenService.Claims claims = service.verify(token, TARGET_SYSTEM);

        assertThat(token.split("\\.")).hasSize(3);
        assertThat(claims.subject()).isEqualTo(SUBJECT);
        assertThat(claims.audience()).contains(TARGET_SYSTEM);
        assertThat(claims.expiresAt()).isAfter(Instant.now());
    }

    /**
     * 篡改签名或目标系统不匹配时必须拒绝令牌。
     */
    @Test
    @DisplayName("R23 统一身份 JWT 拒绝篡改签名与目标系统不匹配的令牌")
    void RIdentityTokenRejectsTamperingAndWrongAudience() {
        IdentityTokenService service = new IdentityTokenService(JWT_SECRET, JWT_ISSUER, JWT_TTL_SECONDS);
        String token = service.issue(SUBJECT, List.of(TARGET_SYSTEM), Instant.now());

        assertThatThrownBy(() -> service.verify(token + TAMPER_SUFFIX, TARGET_SYSTEM))
                .isInstanceOf(IdentityTokenService.InvalidTokenException.class);
        assertThatThrownBy(() -> service.verify(token, OTHER_SYSTEM))
                .isInstanceOf(IdentityTokenService.InvalidTokenException.class);
        String expired = service.issue(SUBJECT, List.of(TARGET_SYSTEM), Instant.now().minusSeconds(JWT_TTL_SECONDS + 10));
        assertThatThrownBy(() -> service.verify(expired, TARGET_SYSTEM))
                .isInstanceOf(IdentityTokenService.InvalidTokenException.class);
    }
}
