package com.melonhub.rbac.infrastructure.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.identity.IdentityTokenService;
import com.melonhub.rbac.infrastructure.security.ClientAddress;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.infrastructure.security.Totp;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CacheGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CacheLookup;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CacheLookupStatus;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CryptoGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.IdentityTokenGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.JsonCodec;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.TransactionRunner;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.WebhookTransport;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DDD 基础设施网关适配：将缓存失效、编程式事务、加密/TOTP、JWT 编解码、JSON 序列化及 HTTP 投递
 * 桥接到 Spring/JDK/Redis 等外围基础设施。
 */
@Component
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class SpringPlatformGatewaysAdapter
        implements CacheGateway, TransactionRunner, CryptoGateway, IdentityTokenGateway, JsonCodec, WebhookTransport {

    private final PermissionCache cache;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readOnlyTx;
    private final BCryptPasswordEncoder adminEncoder = new BCryptPasswordEncoder(12);
    private final String dummyAdminHash;
    private final PasswordEncoder identityPasswordEncoder;
    private final IdentityTokenService identityTokenService;
    private final ObjectMapper json;
    private final HttpClient http;

    public SpringPlatformGatewaysAdapter(PermissionCache cache, PlatformTransactionManager transactionManager,
                                         PasswordEncoder identityPasswordEncoder, IdentityTokenService identityTokenService,
                                         ObjectMapper json) {
        this.cache = cache;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.dummyAdminHash = adminEncoder.encode(Credentials.token());
        this.identityPasswordEncoder = identityPasswordEncoder;
        this.identityTokenService = identityTokenService;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ---- CacheGateway ----
    @Override public String health() { return cache.health(); }
    @Override public void touchUser(long userId) { cache.touchUser(userId); }
    @Override public void touchApplication(long applicationId) { cache.touchApplication(applicationId); }
    @Override public void touchKeys(long applicationId) { cache.touchKeys(applicationId); }
    @Override public void touchDomain(long departmentId, long applicationId) { cache.touchDomain(departmentId, applicationId); }
    @Override public CacheLookup lookupContext(String hash) { return toLookup(cache.context(hash)); }
    @Override public void putContextAfterCommit(String hash, String metaJson, long ttlSeconds) { cache.putContextAfterCommit(hash, metaJson, ttlSeconds); }
    @Override public void putContext(String hash, String metaJson, long ttlSeconds) { cache.putContext(hash, metaJson, ttlSeconds); }
    @Override public void forgetContextAfterCommit(String hash) { cache.forgetContextAfterCommit(hash); }
    @Override public CacheLookup lookupApiKey(String hash) { return toLookup(cache.applicationKey(hash)); }
    @Override public void putApiKey(String hash, String metaJson) { cache.putApplicationKey(hash, metaJson); }
    @Override public void storePayload(String key, String value, long ttlSeconds) { cache.store(key, value, ttlSeconds); }

    private static CacheLookup toLookup(PermissionCache.Lookup lookup) {
        CacheLookupStatus status = switch (lookup.status()) {
            case HIT -> CacheLookupStatus.HIT;
            case MISS -> CacheLookupStatus.MISS;
            case NONE -> CacheLookupStatus.NONE;
            case BYPASS -> CacheLookupStatus.BYPASS;
        };
        return new CacheLookup(status, lookup.meta(), lookup.payload(), lookup.storeKey());
    }

    // ---- TransactionRunner ----
    @Override public <T> T inTransaction(Supplier<T> work) { return writeTx.execute(status -> work.get()); }
    @Override public <T> T inReadOnlyTransaction(Supplier<T> work) { return readOnlyTx.execute(status -> work.get()); }

    // ---- CryptoGateway ----
    @Override public String encodeAdminPassword(String rawPassword) { return adminEncoder.encode(rawPassword); }
    @Override public boolean matchesAdminPassword(String rawPassword, String encodedHash) { return adminEncoder.matches(rawPassword, encodedHash); }
    @Override public void consumeDummyAdminCheck() { adminEncoder.matches("", dummyAdminHash); }
    @Override public String encodeIdentityPassword(String rawPassword) { return identityPasswordEncoder.encode(rawPassword); }
    @Override public boolean matchesIdentityPassword(String rawPassword, String encodedHash) { return identityPasswordEncoder.matches(rawPassword, encodedHash); }
    @Override public String generateToken() { return Credentials.token(); }
    @Override public String generateApiKey() { return Credentials.apiKey(); }
    @Override public String apiKeyPrefix(String rawKey) { return Credentials.prefix(rawKey); }
    @Override public String hashToken(String rawToken) { return Credentials.hash(rawToken); }
    @Override public String hashTokenOrNull(String rawToken) { return Credentials.hashOrNull(rawToken); }
    @Override public String generateTotpSecret() { return Totp.newSecret(); }
    @Override public Long verifyTotp(String secret, String code) { return Totp.verify(secret, code); }
    @Override public String currentClientAddress() { return ClientAddress.current(); }

    // ---- IdentityTokenGateway ----
    @Override
    public String issue(String subject, List<String> audience, Instant issuedAt) {
        return identityTokenService.issue(subject, audience, issuedAt);
    }

    @Override
    public VerifiedToken verify(String token, String expectedAudience) {
        try {
            var claims = identityTokenService.verify(token, expectedAudience);
            return new VerifiedToken(claims.subject(), claims.audience(), claims.issuedAt(), claims.expiresAt(), claims.tokenId());
        } catch (IdentityTokenService.InvalidTokenException e) {
            throw DomainException.unauthorized("UNAUTHORIZED", "请先登录或令牌已失效。");
        }
    }

    // ---- JsonCodec ----
    @Override
    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public <T> T readOrNull(String value, Class<T> type) {
        if (value == null) return null;
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException | RuntimeException e) {
            return null;
        }
    }

    // ---- WebhookTransport ----
    @Override
    public int post(String url, String payload, String eventType, String eventId, String signatureHeader, int timeoutMillis) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "application/json")
                .header("MelonHub-Event", eventType)
                .header("MelonHub-Delivery", eventId)
                .header("MelonHub-Signature", signatureHeader)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        var response = http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        try {
            return response.get(timeoutMillis, TimeUnit.MILLISECONDS).statusCode();
        } finally {
            response.cancel(true);
        }
    }
}
