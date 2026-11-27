package com.melonhub.rbac.service.ddd.application.context;

import com.melonhub.rbac.service.ddd.application.shared.DddBoundary;
import com.melonhub.rbac.service.ddd.domain.model.PermissionContextAggregate;
import com.melonhub.rbac.service.ddd.domain.model.PermissionContextAggregate.ContextSubject;
import com.melonhub.rbac.service.ddd.domain.model.PermissionContextAggregate.EffectiveRole;
import com.melonhub.rbac.service.ddd.domain.model.PermissionContextAggregate.EffectiveSnapshot;
import com.melonhub.rbac.service.ddd.domain.repository.PermissionContextRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CacheGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CacheLookup;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CacheLookupStatus;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CryptoGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.JsonCodec;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.TransactionRunner;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.context.ContextModels.CheckResult;
import com.melonhub.rbac.service.port.context.ContextModels.ContextResult;
import com.melonhub.rbac.service.port.context.ContextModels.ContextRoleResult;
import com.melonhub.rbac.service.port.context.ContextUseCase;
import com.melonhub.rbac.service.port.context.IssueContextCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacUseCase;
import com.melonhub.rbac.service.port.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DDD 权限上下文应用服务：将部门切换保护、激活角色子集规则与鉴权评估委托给 {@link PermissionContextAggregate}。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class DddContextService implements ContextUseCase {
    private record Meta(String u, String d, String a, long v, long exp) {
        long user() { return Long.parseLong(u); }
        long department() { return Long.parseLong(d); }
        long application() { return Long.parseLong(a); }
        boolean usableFor(long app) { return application() == app && exp > Instant.now().getEpochSecond(); }
    }

    private record Payload(boolean valid, List<ContextRoleResult> roles, List<String> permissions, Long refreshAt) {
        static final Payload INVALID = new Payload(false, List.of(), List.of(), null);
        boolean lapsed() { return refreshAt != null && refreshAt <= Instant.now().getEpochSecond(); }
    }

    private record Access(Meta meta, Payload payload) {}

    private final PermissionContextRepositoryPort repository;
    private final DomainRbacUseCase domainRbac;
    private final AuditUseCase audit;
    private final CacheGateway cache;
    private final CryptoGateway crypto;
    private final JsonCodec json;
    private final TransactionRunner transactions;

    public DddContextService(PermissionContextRepositoryPort repository, DomainRbacUseCase domainRbac,
                             AuditUseCase audit, CacheGateway cache, CryptoGateway crypto,
                             JsonCodec json, TransactionRunner transactions) {
        this.repository = repository;
        this.domainRbac = domainRbac;
        this.audit = audit;
        this.cache = cache;
        this.crypto = crypto;
        this.json = json;
        this.transactions = transactions;
    }

    @Override
    @Transactional
    public ContextResult issueContext(long app, IssueContextCommand input, String actor) {
        return DddBoundary.execute(() -> {
            long departmentId = input.getDepartmentId();
            String externalId = input.getExternalId();
            domainRbac.domain(departmentId, app);
            PermissionContextRepositoryPort.LockedUserContextRow user = repository.lockUserByExternalId(externalId)
                    .orElseThrow(() -> DomainException.missing("用户不存在"));
            boolean appEnabled = repository.isApplicationEnabled(app);
            boolean member = repository.isMember(user.userId(), departmentId);
            List<Long> assigned = repository.assignedRoleIds(user.userId(), departmentId, app);
            ContextSubject subject = new ContextSubject(user.userId(), user.enabled(), user.activeDepartmentId(),
                    user.contextVersion(), appEnabled, member, assigned);
            PermissionContextAggregate.IssuancePlan plan = PermissionContextAggregate.planIssuance(
                    app, departmentId, input.getActiveRoleIds(), input.getSwitchDepartment(),
                    subject, Instant.now(), CONTEXT_SECONDS);

            if (plan.switchedDepartment()) {
                repository.switchDepartment(plan.userId(), plan.departmentId(), plan.contextVersion());
                cache.touchUser(plan.userId());
            }
            repository.clearStaleContexts(plan.userId(), plan.contextVersion());
            String token = crypto.generateToken();
            String hash = crypto.hashToken(token);
            repository.createContext(hash, plan.userId(), plan.departmentId(), plan.applicationId(),
                    plan.contextVersion(), plan.expiresAt());
            repository.activateRoles(hash, plan.activeRoleIds());
            audit.audit(actor, "CONTEXT_ISSUED", externalId, plan.departmentId(), plan.applicationId(),
                    "激活角色 " + plan.activeRoleIds().size() + " 个" + (plan.switchedDepartment() ? "，切换当前部门" : ""));
            Meta meta = new Meta(String.valueOf(plan.userId()), String.valueOf(plan.departmentId()),
                    String.valueOf(plan.applicationId()), plan.contextVersion(), plan.expiresAt().getEpochSecond());
            Access access = new Access(meta, compute(hash, meta));
            cache.putContextAfterCommit(hash, json.write(meta), CONTEXT_SECONDS);
            return contextResult(access, token);
        });
    }

    @Override
    public ContextResult effective(long app, String token) {
        return contextResult(resolve(app, token), null);
    }

    @Override
    public CheckResult check(long app, String token, List<String> requested) {
        Access access = resolve(app, token);
        EffectiveSnapshot snapshot = new EffectiveSnapshot(
                access.meta().user(), access.meta().department(), access.meta().application(),
                access.meta().v(), Instant.ofEpochSecond(access.meta().exp()),
                List.of(), access.payload().permissions(),
                access.payload().refreshAt() == null ? null : Instant.ofEpochSecond(access.payload().refreshAt()));
        PermissionContextAggregate.CheckEvaluation outcome = snapshot.evaluate(requested);
        return new CheckResult(outcome.allowed(), outcome.decisions(), outcome.contextVersion());
    }

    @Override
    @Transactional
    public void revoke(long app, String token, String actor) {
        Access access = resolve(app, token);
        String hash = crypto.hashToken(token);
        repository.revokeByHash(hash, app);
        cache.touchUser(access.meta().user());
        cache.forgetContextAfterCommit(hash);
        audit.audit(actor, "CONTEXT_REVOKED", "user:" + access.meta().u(), access.meta().department(), app, "");
    }

    private Access resolve(long app, String token) {
        String hash = crypto.hashTokenOrNull(token);
        if (hash == null) {
            throw ApiException.contextInvalid();
        }
        CacheLookup lookup = cache.lookupContext(hash);
        Meta cachedMeta = lookup.meta() == null ? null : json.readOrNull(lookup.meta(), Meta.class);
        if (lookup.status() == CacheLookupStatus.HIT && cachedMeta != null) {
            Payload payload = json.readOrNull(lookup.payload(), Payload.class);
            if (payload != null && !payload.lapsed()) {
                if (!cachedMeta.usableFor(app) || !payload.valid()) {
                    throw ApiException.contextInvalid();
                }
                return new Access(cachedMeta, payload);
            }
        }
        if (cachedMeta != null && !cachedMeta.usableFor(app)) {
            throw ApiException.contextInvalid();
        }
        Access loaded = transactions.inReadOnlyTransaction(() -> load(hash));
        String storeKey = lookup.status() == CacheLookupStatus.MISS ? lookup.storeKey() : null;
        if (loaded == null) {
            cache.storePayload(storeKey, json.write(Payload.INVALID), CacheGateway.DEFAULT_TTL_SECONDS);
            throw ApiException.contextInvalid();
        }
        long now = Instant.now().getEpochSecond();
        long remaining = loaded.meta().exp() - now;
        if (lookup.status() == CacheLookupStatus.NONE) {
            cache.putContext(hash, json.write(loaded.meta()), remaining);
        }
        long ttl = Math.min(CacheGateway.DEFAULT_TTL_SECONDS, remaining);
        if (loaded.payload().refreshAt() != null) {
            ttl = Math.min(ttl, loaded.payload().refreshAt() - now);
        }
        cache.storePayload(storeKey, json.write(loaded.payload()), ttl);
        if (!loaded.meta().usableFor(app) || !loaded.payload().valid()) {
            throw ApiException.contextInvalid();
        }
        return loaded;
    }

    private Access load(String hash) {
        Optional<PermissionContextRepositoryPort.StoredContextState> found = repository.contextState(hash);
        if (found.isEmpty()) {
            return null;
        }
        PermissionContextRepositoryPort.StoredContextState row = found.get();
        Meta meta = new Meta(String.valueOf(row.userId()), String.valueOf(row.departmentId()),
                String.valueOf(row.applicationId()), row.contextVersion(), row.expiresAt().getEpochSecond());
        return new Access(meta, row.valid() ? compute(hash, meta) : Payload.INVALID);
    }

    private Payload compute(String hash, Meta meta) {
        PermissionContextRepositoryPort.EffectiveAccess effective =
                repository.effectiveAccess(meta.user(), hash, meta.department(), meta.application());
        List<ContextRoleResult> roles = effective.roles().stream()
                .map(r -> new ContextRoleResult(r.id(), r.name(), r.code())).toList();
        List<Instant> roleExpiries = effective.roles().stream()
                .map(EffectiveRole::expiresAt).filter(Objects::nonNull).toList();
        Instant earliest = roleExpiries.isEmpty() ? null : PermissionContextAggregate.earliestRefreshAt(null, roleExpiries);
        Long refreshAt = earliest == null ? null : earliest.getEpochSecond();
        return new Payload(true, roles, effective.permissions(), refreshAt);
    }

    private static ContextResult contextResult(Access access, String token) {
        Meta meta = access.meta();
        return new ContextResult(token, meta.user(), meta.department(), meta.application(), meta.v(),
                Instant.ofEpochSecond(meta.exp()), access.payload().roles(), access.payload().permissions());
    }
}
