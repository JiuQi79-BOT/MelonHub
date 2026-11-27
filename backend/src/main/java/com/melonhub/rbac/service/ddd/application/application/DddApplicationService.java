package com.melonhub.rbac.service.ddd.application.application;

import com.melonhub.rbac.service.ddd.application.shared.DddBoundary;
import com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.PermissionContextRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CacheGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CryptoGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.DomainEventPublisher;
import com.melonhub.rbac.service.ddd.domain.repository.RbacRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApiKeyCreatedResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApiKeyResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApplicationCreatedResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApplicationResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.CreateApiKeyCommand;
import com.melonhub.rbac.service.port.application.ApplicationModels.SaveApplicationCommand;
import com.melonhub.rbac.service.port.application.ApplicationUseCase;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.common.PageResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DDD 业务软件应用服务：协调软件删除守卫、API Key 配额、紧急轮换与限额更新。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class DddApplicationService implements ApplicationUseCase {
    private static final int MAX_ACTIVE_KEYS = 10;

    private final RbacRepositoryPort repository;
    private final PermissionContextRepositoryPort contexts;
    private final CacheGateway cache;
    private final AuditUseCase audit;
    private final DomainEventPublisher events;
    private final CryptoGateway crypto;

    public DddApplicationService(RbacRepositoryPort repository, PermissionContextRepositoryPort contexts,
                                 CacheGateway cache, AuditUseCase audit, DomainEventPublisher events,
                                 CryptoGateway crypto) {
        this.repository = repository;
        this.contexts = contexts;
        this.cache = cache;
        this.audit = audit;
        this.events = events;
        this.crypto = crypto;
    }

    @Override
    public List<ApplicationResult> applications(Collection<Long> visible) {
        return repository.applications(visible).stream()
                .map(a -> new ApplicationResult(a.id(), a.name(), a.code(), a.description(), a.owner(), a.enabled(),
                        a.rateLimit(), a.createdAt(), a.roleCount(), a.permissionCount(), a.activeKeyCount()))
                .toList();
    }

    @Override
    @Transactional
    public ApplicationCreatedResult application(SaveApplicationCommand body, String actor) {
        long id = repository.createApplication(body.name(), body.code(), text(body.description()), text(body.owner()));
        ApiKeyCreatedResult key = issueKey(id, "默认密钥", null, actor);
        audit.audit(actor, "APPLICATION_CREATED", body.name(), null, id,
                "编码 " + body.code() + "，已生成默认密钥 " + key.prefix());
        return new ApplicationCreatedResult(id, key.apiKey(), key.id());
    }

    @Override
    @Transactional
    public long updateApplication(long id, SaveApplicationCommand body, String actor) {
        return DddBoundary.execute(() -> {
            if (repository.updateApplication(id, body.name(), body.code(), text(body.description()), text(body.owner())) == 0) {
                throw DomainException.missing("软件不存在");
            }
            audit.audit(actor, "APPLICATION_UPDATED", body.name(), null, id, "编码 " + body.code());
            return id;
        });
    }

    @Override
    @Transactional
    public void applicationStatus(long id, boolean enabled, String actor) {
        DddBoundary.run(() -> {
            if (repository.updateApplicationStatus(id, enabled) == 0) {
                throw DomainException.missing("软件不存在");
            }
            if (!enabled) {
                contexts.revokeApplication(id);
            }
            cache.touchApplication(id);
            events.emit(id, enabled ? "application.enabled" : "application.disabled", Map.of());
            audit.audit(actor, enabled ? "APPLICATION_ENABLED" : "APPLICATION_DISABLED", "application:" + id, null, id,
                    enabled ? "" : "已清除该软件全部权限上下文");
        });
    }

    @Override
    @Transactional
    public void deleteApplication(long id, String actor) {
        DddBoundary.run(() -> {
            RbacRepositoryPort.EntityLabel label = repository.applicationLabel(id)
                    .orElseThrow(() -> DomainException.missing("软件不存在"));
            DomainAuthorizationAggregate.assertApplicationDeletable(repository.applicationUsage(id));
            contexts.revokeApplication(id);
            cache.touchApplication(id);
            repository.deleteApplication(id);
            audit.audit(actor, "APPLICATION_DELETED", label.name(), null, id, "编码 " + label.code());
        });
    }

    @Override
    public List<ApiKeyResult> keys(long app) {
        return DddBoundary.execute(() -> {
            if (!repository.applicationExists(app)) {
                throw DomainException.missing("软件不存在");
            }
            return repository.applicationKeys(app).stream()
                    .map(k -> new ApiKeyResult(k.id(), k.name(), k.prefix(), k.createdBy(), k.createdAt(),
                            k.expiresAt(), k.lastUsedAt(), k.revokedAt(), k.status()))
                    .toList();
        });
    }

    @Override
    @Transactional
    public ApiKeyCreatedResult createKey(long app, CreateApiKeyCommand body, String actor) {
        return DddBoundary.execute(() -> {
            if (!repository.lockApplication(app)) {
                throw DomainException.missing("软件不存在");
            }
            if (repository.lockedActiveKeyCount(app) >= MAX_ACTIVE_KEYS) {
                throw DomainException.conflict("每个软件最多保留 " + MAX_ACTIVE_KEYS + " 个有效密钥，请先吊销不用的密钥");
            }
            ApiKeyCreatedResult key = issueKey(app, body.name().strip(), body.expiresInDays(), actor);
            audit.audit(actor, "API_KEY_CREATED", body.name(), null, app,
                    key.prefix() + (body.expiresInDays() == null ? "，永不过期" : "，" + body.expiresInDays() + " 天后过期"));
            return key;
        });
    }

    @Override
    @Transactional
    public void revokeKey(long app, long key, String actor) {
        DddBoundary.run(() -> {
            String name = repository.applicationKeyName(app, key).orElseThrow(() -> DomainException.missing("密钥不存在"));
            if (repository.revokeApplicationKey(app, key) == 0) {
                throw DomainException.conflict("密钥已被吊销");
            }
            cache.touchKeys(app);
            events.emit(app, "apikey.revoked", Map.of("keyId", key, "name", name));
            audit.audit(actor, "API_KEY_REVOKED", name, null, app, "key:" + key);
        });
    }

    @Override
    @Transactional
    public String rotateKey(long app, String actor) {
        return DddBoundary.execute(() -> {
            if (!repository.lockApplication(app)) {
                throw DomainException.missing("软件不存在");
            }
            int revoked = repository.revokeAllApplicationKeys(app);
            ApiKeyCreatedResult key = issueKey(app, "轮换密钥 " + LocalDate.now(PageResult.ZONE), null, actor);
            cache.touchKeys(app);
            events.emit(app, "apikey.revoked", Map.of("rotated", true, "revoked", revoked));
            audit.audit(actor, "API_KEY_ROTATED", "application:" + app, null, app,
                    "吊销 " + revoked + " 个旧密钥，新密钥 " + key.prefix());
            return key.apiKey();
        });
    }

    @Override
    public int applicationRateLimit(long id, int fallback) {
        return repository.applicationRateLimit(id, fallback);
    }

    @Override
    @Transactional
    public void applicationRateLimit(long id, Integer perSecond, String actor) {
        DddBoundary.run(() -> {
            if (repository.updateApplicationRateLimit(id, perSecond) == 0) {
                throw DomainException.missing("软件不存在");
            }
            audit.audit(actor, "APPLICATION_RATE_LIMIT_UPDATED", "application:" + id, null, id,
                    perSecond == null ? "恢复平台默认" : perSecond == 0 ? "不限制" : perSecond + " 次/秒");
        });
    }

    private ApiKeyCreatedResult issueKey(long app, String name, Integer days, String actor) {
        String key = crypto.generateApiKey();
        String prefix = crypto.apiKeyPrefix(key);
        Instant expires = days == null ? null : Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(days, ChronoUnit.DAYS);
        long id = repository.createApplicationKey(app, name, prefix, crypto.hashToken(key), actor, expires);
        return new ApiKeyCreatedResult(id, name, prefix, key, expires);
    }

    private static String text(String value) {
        return Objects.requireNonNullElse(value, "").strip();
    }
}
