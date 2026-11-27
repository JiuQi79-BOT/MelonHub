package com.melonhub.rbac.service.structured.application;

import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.persistence.entity.Application;
import com.melonhub.rbac.infrastructure.persistence.entity.ApplicationKey;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationKeyRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionContextRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApiKeyCreatedResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApiKeyResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApplicationCreatedResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApplicationResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.CreateApiKeyCommand;
import com.melonhub.rbac.service.port.application.ApplicationModels.SaveApplicationCommand;
import com.melonhub.rbac.service.port.application.ApplicationUseCase;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structured 业务软件服务：管理软件接入、启停删除、多 API Key 轮换及每秒限额。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "structured", matchIfMissing = true)
public class StructuredApplicationService implements ApplicationUseCase {
    private static final int MAX_ACTIVE_KEYS = 10;

    private final ApplicationRepository applications;
    private final ApplicationKeyRepository applicationKeys;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final PermissionContextRepository contexts;
    private final PermissionCache cache;
    private final AuditUseCase audit;
    private final WebhookUseCase webhooks;

    public StructuredApplicationService(ApplicationRepository applications, ApplicationKeyRepository applicationKeys,
                                        RoleRepository roles, PermissionRepository permissions,
                                        PermissionContextRepository contexts, PermissionCache cache,
                                        AuditUseCase audit, WebhookUseCase webhooks) {
        this.applications = applications;
        this.applicationKeys = applicationKeys;
        this.roles = roles;
        this.permissions = permissions;
        this.contexts = contexts;
        this.cache = cache;
        this.audit = audit;
        this.webhooks = webhooks;
    }

    @Override
    public List<ApplicationResult> applications(Collection<Long> visible) {
        List<ApplicationRepository.SummaryRow> rows = applications.findVisibleSummaries(visible);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(ApplicationRepository.SummaryRow::id).toList();
        Map<Long, Long> roleCounts = new HashMap<>();
        for (RoleRepository.GroupCount c : roles.countByApplicationIds(ids)) {
            roleCounts.put(c.getGroupId(), c.getCount());
        }
        Map<Long, Long> permCounts = new HashMap<>();
        for (PermissionRepository.ApplicationCount c : permissions.countByApplicationIds(ids)) {
            permCounts.put(c.getApplicationId(), c.getCount());
        }
        Map<Long, Long> keyCounts = new HashMap<>();
        for (ApplicationKeyRepository.ApplicationCount c : applicationKeys.countActiveByApplicationIds(ids)) {
            keyCounts.put(c.getApplicationId(), c.getCount());
        }
        return rows.stream()
                .map(a -> new ApplicationResult(a.id(), a.name(), a.code(), a.description(), a.owner(), a.enabled(),
                        a.rateLimit(), a.createdAt(),
                        roleCounts.getOrDefault(a.id(), 0L),
                        permCounts.getOrDefault(a.id(), 0L),
                        keyCounts.getOrDefault(a.id(), 0L)))
                .toList();
    }

    @Override
    @Transactional
    public ApplicationCreatedResult application(SaveApplicationCommand body, String actor) {
        long id = applications.saveAndFlush(new Application()
                .setName(body.name()).setCode(body.code())
                .setDescription(text(body.description())).setOwner(text(body.owner())).setEnabled(true)).getId();
        ApiKeyCreatedResult key = newKey(id, "默认密钥", null, actor);
        audit.audit(actor, "APPLICATION_CREATED", body.name(), null, id,
                "编码 " + body.code() + "，已生成默认密钥 " + key.prefix());
        return new ApplicationCreatedResult(id, key.apiKey(), key.id());
    }

    @Override
    @Transactional
    public long updateApplication(long id, SaveApplicationCommand body, String actor) {
        if (applications.updateProfile(id, body.name(), body.code(), text(body.description()), text(body.owner())) == 0) {
            throw ApiException.missing("软件不存在");
        }
        audit.audit(actor, "APPLICATION_UPDATED", body.name(), null, id, "编码 " + body.code());
        return id;
    }

    @Override
    @Transactional
    public void applicationStatus(long id, boolean enabled, String actor) {
        if (applications.updateStatus(id, enabled) == 0) {
            throw ApiException.missing("软件不存在");
        }
        if (!enabled) {
            contexts.clearByApplicationId(id);
        }
        cache.touchApplication(id);
        webhooks.emit(id, enabled ? "application.enabled" : "application.disabled", Map.of());
        audit.audit(actor, enabled ? "APPLICATION_ENABLED" : "APPLICATION_DISABLED", "application:" + id, null, id,
                enabled ? "" : "已清除该软件全部权限上下文");
    }

    @Override
    @Transactional
    public void deleteApplication(long id, String actor) {
        ApplicationRepository.Label label = applications.findLabelById(id)
                .orElseThrow(() -> ApiException.missing("软件不存在"));
        List<String> parts = new ArrayList<>();
        long roleCount = roles.countByApplicationId(id);
        long permCount = permissions.countByApplicationId(id);
        if (roleCount > 0) parts.add(roleCount + " 个角色");
        if (permCount > 0) parts.add(permCount + " 个权限");
        if (!parts.isEmpty()) {
            throw ApiException.conflict("软件下还有 " + String.join("、", parts) + "，请先删除，或改为停用");
        }
        contexts.clearByApplicationId(id);
        cache.touchApplication(id);
        applications.deleteByIdReturningCount(id);
        audit.audit(actor, "APPLICATION_DELETED", label.getName(), null, id, "编码 " + label.getCode());
    }

    @Override
    public List<ApiKeyResult> keys(long app) {
        if (!applications.existsById(app)) {
            throw ApiException.missing("软件不存在");
        }
        return applicationKeys.findSummariesByApplicationId(app).stream()
                .map(k -> new ApiKeyResult(k.getId(), k.getName(), k.getPrefix(), k.getCreatedBy(), k.getCreatedAt(),
                        k.getExpiresAt(), k.getLastUsedAt(), k.getRevokedAt(), k.getStatus()))
                .toList();
    }

    @Override
    @Transactional
    public ApiKeyCreatedResult createKey(long app, CreateApiKeyCommand body, String actor) {
        if (!applications.lockById(app)) {
            throw ApiException.missing("软件不存在");
        }
        if (applicationKeys.countLockedActive(app) >= MAX_ACTIVE_KEYS) {
            throw ApiException.conflict("每个软件最多保留 " + MAX_ACTIVE_KEYS + " 个有效密钥，请先吊销不用的密钥");
        }
        ApiKeyCreatedResult key = newKey(app, body.name().strip(), body.expiresInDays(), actor);
        audit.audit(actor, "API_KEY_CREATED", body.name(), null, app,
                key.prefix() + (body.expiresInDays() == null ? "，永不过期" : "，" + body.expiresInDays() + " 天后过期"));
        return key;
    }

    @Override
    @Transactional
    public void revokeKey(long app, long key, String actor) {
        String name = applicationKeys.findNameByIdAndApplicationId(key, app)
                .orElseThrow(() -> ApiException.missing("密钥不存在"));
        if (applicationKeys.revokeOne(key, app) == 0) {
            throw ApiException.conflict("密钥已被吊销");
        }
        cache.touchKeys(app);
        webhooks.emit(app, "apikey.revoked", Map.of("keyId", key, "name", name));
        audit.audit(actor, "API_KEY_REVOKED", name, null, app, "key:" + key);
    }

    @Override
    @Transactional
    public String rotateKey(long app, String actor) {
        if (!applications.lockById(app)) {
            throw ApiException.missing("软件不存在");
        }
        int revoked = applicationKeys.revokeAll(app);
        ApiKeyCreatedResult key = newKey(app, "轮换密钥 " + LocalDate.now(PageResult.ZONE), null, actor);
        cache.touchKeys(app);
        webhooks.emit(app, "apikey.revoked", Map.of("rotated", true, "revoked", revoked));
        audit.audit(actor, "API_KEY_ROTATED", "application:" + app, null, app,
                "吊销 " + revoked + " 个旧密钥，新密钥 " + key.prefix());
        return key.apiKey();
    }

    @Override
    public int applicationRateLimit(long id, int fallback) {
        return applications.findRateLimitById(id).orElse(fallback);
    }

    @Override
    @Transactional
    public void applicationRateLimit(long id, Integer perSecond, String actor) {
        if (applications.updateRateLimit(id, perSecond) == 0) {
            throw ApiException.missing("软件不存在");
        }
        audit.audit(actor, "APPLICATION_RATE_LIMIT_UPDATED", "application:" + id, null, id,
                perSecond == null ? "恢复平台默认" : perSecond == 0 ? "不限制" : perSecond + " 次/秒");
    }

    private ApiKeyCreatedResult newKey(long app, String name, Integer days, String actor) {
        String key = Credentials.apiKey();
        String prefix = Credentials.prefix(key);
        Instant expires = days == null ? null : Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(days, ChronoUnit.DAYS);
        long id = applicationKeys.saveAndFlush(new ApplicationKey()
                .setApplication(applications.getReferenceById(app))
                .setName(name).setKeyPrefix(prefix).setKeyHash(Credentials.hash(key))
                .setCreatedBy(truncate(actor, 100)).setExpiresAt(expires)).getId();
        return new ApiKeyCreatedResult(id, name, prefix, key, expires);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String text(String value) {
        return Objects.requireNonNullElse(value, "").strip();
    }
}
