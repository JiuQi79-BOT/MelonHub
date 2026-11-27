package com.melonhub.rbac.infrastructure.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import com.melonhub.rbac.service.port.error.ApiException;

/**
 * 为课程管理系统签发和校验统一身份 JWT。
 *
 * <p>令牌只携带身份认证所需的 subject、aud、iss、iat、exp 和 jti，角色与权限由
 * {@link com.melonhub.rbac.service.port.identity.IdentityUseCase} 按数据库授权关系查询。</p>
 */
public final class IdentityTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final TypeReference<Map<String, Object>> CLAIMS_TYPE = new TypeReference<>() {};

    private final byte[] secret;
    private final String issuer;
    private final long expiresInSeconds;
    private final ObjectMapper objectMapper;

    /**
     * 创建令牌服务。
     *
     * @param secret HMAC 密钥
     * @param issuer 令牌签发者
     * @param expiresInSeconds 默认有效期
     */
    public IdentityTokenService(String secret, String issuer, long expiresInSeconds) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("IDENTITY_JWT_SECRET must contain at least 32 characters");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("IDENTITY_JWT_ISSUER must not be blank");
        }
        if (expiresInSeconds < 1) {
            throw new IllegalArgumentException("IDENTITY_JWT_EXPIRES_SECONDS must be positive");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.expiresInSeconds = expiresInSeconds;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 签发一个面向指定系统的 JWT。
     *
     * @param subject 统一身份用户标识
     * @param audience 目标系统列表
     * @param issuedAt 签发时间
     * @return JWT 紧凑字符串
     */
    public String issue(String subject, List<String> audience, java.time.Instant issuedAt) {
        if (subject == null || subject.isBlank() || audience == null || audience.isEmpty()) {
            throw new IllegalArgumentException("Identity token subject and audience are required");
        }
        long issued = issuedAt.getEpochSecond();
        long expires = issued + expiresInSeconds;
        Map<String, Object> claims = Map.of(
                "sub", subject,
                "aud", audience,
                "iss", issuer,
                "iat", issued,
                "exp", expires,
                "jti", UUID.randomUUID().toString()
        );
        try {
            String encodedHeader = BASE64_ENCODER.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8));
            String encodedClaims = BASE64_ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
            String signingInput = encodedHeader + "." + encodedClaims;
            return signingInput + "." + BASE64_ENCODER.encodeToString(sign(signingInput));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize identity token", exception);
        }
    }

    /**
     * 校验 JWT 签名、签发者、有效期和目标系统。
     *
     * @param token JWT 紧凑字符串
     * @param expectedAudience 调用方目标系统
     * @return 已校验的声明
     */
    public Claims verify(String token, String expectedAudience) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.");
            if (parts.length != 3) {
                throw invalid();
            }
            String signingInput = parts[0] + "." + parts[1];
            byte[] expectedSignature = sign(signingInput);
            byte[] actualSignature = BASE64_DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                throw invalid();
            }
            Map<String, Object> claims = objectMapper.readValue(BASE64_DECODER.decode(parts[1]), CLAIMS_TYPE);
            String subject = stringClaim(claims, "sub");
            String claimIssuer = stringClaim(claims, "iss");
            long issuedAt = longClaim(claims, "iat");
            long expiresAt = longClaim(claims, "exp");
            String jti = stringClaim(claims, "jti");
            List<String> audience = audienceClaim(claims.get("aud"));
            long now = java.time.Instant.now().getEpochSecond();
            if (!issuer.equals(claimIssuer) || !audience.contains(expectedAudience) || expiresAt <= now || issuedAt > now + 60) {
                throw invalid();
            }
            return new Claims(subject, audience, java.time.Instant.ofEpochSecond(issuedAt), java.time.Instant.ofEpochSecond(expiresAt), jti);
        } catch (IdentityTokenService.InvalidTokenException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign identity token", exception);
        }
    }

    private static String stringClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw invalid();
        }
        return string;
    }

    private static long longClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof Number number)) {
            throw invalid();
        }
        return number.longValue();
    }

    private static List<String> audienceClaim(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw invalid();
        }
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static InvalidTokenException invalid() {
        return new InvalidTokenException();
    }

    /**
     * 已校验的 JWT 声明。
     */
    public record Claims(
            String subject,
            List<String> audience,
            java.time.Instant issuedAt,
            java.time.Instant expiresAt,
            String tokenId
    ) {
    }

    /**
     * JWT 校验失败异常。
     */
    public static final class InvalidTokenException extends ApiException {
        private InvalidTokenException() {
            super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录或令牌已失效。");
        }
    }
}
