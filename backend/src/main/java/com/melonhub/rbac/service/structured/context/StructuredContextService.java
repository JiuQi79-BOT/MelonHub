package com.melonhub.rbac.service.structured.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ContextRoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.MembershipRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionContextRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleInheritanceRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RolePermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRoleRepository;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.context.ContextModels.CheckResult;
import com.melonhub.rbac.service.port.context.ContextModels.ContextResult;
import com.melonhub.rbac.service.port.context.ContextModels.ContextRoleResult;
import com.melonhub.rbac.service.port.context.ContextUseCase;
import com.melonhub.rbac.service.port.context.IssueContextCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacUseCase;
import com.melonhub.rbac.service.port.error.ApiException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Structured 权限上下文服务：签发在线上下文、基于只读一致性快照与 Redis 代标记缓存执行无锁鉴权。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "structured", matchIfMissing = true)
public class StructuredContextService implements ContextUseCase {
    record Meta(String u, String d, String a, long v, long exp) {
        long user() { return Long.parseLong(u); }
        long department() { return Long.parseLong(d); }
        long application() { return Long.parseLong(a); }
        boolean usableFor(long app) { return application() == app && exp > Instant.now().getEpochSecond(); }
    }

    record Payload(boolean valid, List<ContextRoleResult> roles, List<String> permissions, Long refreshAt) {
        static final Payload INVALID = new Payload(false, List.of(), List.of(), null);
        boolean lapsed() { return refreshAt != null && refreshAt <= Instant.now().getEpochSecond(); }
    }

    record Access(Meta meta, Payload payload) {}

    private final PermissionContextRepository contexts;
    private final ContextRoleRepository contextRoles;
    private final UserRepository users;
    private final ApplicationRepository applications;
    private final DepartmentRepository departments;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final RoleInheritanceRepository roleInheritances;
    private final UserRoleRepository userRoles;
    private final DomainRbacUseCase domainRbac;
    private final AuditUseCase audit;
    private final PermissionCache cache;
    private final ObjectMapper json;
    private final TransactionTemplate snapshot;

    public StructuredContextService(PermissionContextRepository contexts, ContextRoleRepository contextRoles,
                                    UserRepository users, ApplicationRepository applications,
                                    DepartmentRepository departments, MembershipRepository memberships,
                                    RoleRepository roles, RolePermissionRepository rolePermissions,
                                    RoleInheritanceRepository roleInheritances, UserRoleRepository userRoles,
                                    DomainRbacUseCase domainRbac, AuditUseCase audit,
                                    PermissionCache cache, ObjectMapper json,
                                    PlatformTransactionManager transactions) {
        this.contexts = contexts;
        this.contextRoles = contextRoles;
        this.users = users;
        this.applications = applications;
        this.departments = departments;
        this.memberships = memberships;
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.roleInheritances = roleInheritances;
        this.userRoles = userRoles;
        this.domainRbac = domainRbac;
        this.audit = audit;
        this.cache = cache;
        this.json = json;
        this.snapshot = new TransactionTemplate(transactions);
        this.snapshot.setReadOnly(true);
    }

    @Override
    @Transactional
    public ContextResult issueContext(long app, IssueContextCommand input, String actor) {
        long departmentId = input.getDepartmentId();
        String externalId = input.getExternalId();
        domainRbac.domain(departmentId, app);
        UserRepository.LockedUserRow user = users.lockRowByExternalId(externalId)
                .orElseThrow(() -> ApiException.missing("用户不存在"));
        long userId = user.id();
        if (!user.enabled()) {
            throw ApiException.forbidden("用户已停用");
        }
        if (!applications.existsByIdAndEnabledTrue(app)) {
            throw ApiException.forbidden("软件已停用");
        }
        if (!memberships.existsByUserIdAndDepartmentId(userId, departmentId)) {
            throw ApiException.forbidden("用户不属于目标部门");
        }
        List<Long> assigned = userRoles.findActiveAssignedRoleIds(userId, departmentId, app);
        List<Long> active = input.getActiveRoleIds() == null
                ? assigned
                : new ArrayList<>(new LinkedHashSet<>(input.getActiveRoleIds()));
        if (!new HashSet<>(assigned).containsAll(active)) {
            throw ApiException.forbidden("激活角色必须为用户在当前隔离域内已分配的角色");
        }
        long version = user.contextVersion();
        Long current = user.activeDepartmentId();
        boolean switched = current == null || current != departmentId;
        if (switched && current != null && Boolean.FALSE.equals(input.getSwitchDepartment())) {
            throw ApiException.conflict("DEPARTMENT_MISMATCH",
                    "用户当前部门为 #" + current + "，在部门 #" + departmentId + " 创建上下文需要显式切换部门");
        }
        if (switched) {
            version++;
            users.switchDepartment(userId, departments.getReferenceById(departmentId), version);
            cache.touchUser(userId);
        }
        contexts.deleteStaleForUser(userId, version);
        String token = Credentials.token();
        String hash = Credentials.hash(token);
        Instant expires = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(CONTEXT_SECONDS);
        contexts.persistContext(hash, users.getReferenceById(userId), departmentId, app, version, expires);
        contextRoles.activateRoles(hash, contexts.getReferenceById(hash), active, roles::getReferenceById);
        audit.audit(actor, "CONTEXT_ISSUED", externalId, departmentId, app,
                "激活角色 " + active.size() + " 个" + (switched ? "，切换当前部门" : ""));
        Meta meta = new Meta(String.valueOf(userId), String.valueOf(departmentId), String.valueOf(app),
                version, expires.getEpochSecond());
        Access access = new Access(meta, compute(hash, meta));
        cache.putContextAfterCommit(hash, write(meta), CONTEXT_SECONDS);
        return contextResult(access, token);
    }

    @Override
    public ContextResult effective(long app, String token) {
        return contextResult(resolve(app, token), null);
    }

    @Override
    public CheckResult check(long app, String token, List<String> requested) {
        Access access = resolve(app, token);
        Set<String> granted = new HashSet<>(access.payload().permissions());
        Map<String, Boolean> decisions = new LinkedHashMap<>();
        for (String permission : requested) {
            decisions.put(permission, granted.contains(permission));
        }
        return new CheckResult(decisions.values().stream().allMatch(Boolean::booleanValue), decisions, access.meta().v());
    }

    @Override
    @Transactional
    public void revoke(long app, String token, String actor) {
        Access access = resolve(app, token);
        String hash = Credentials.hash(token);
        contexts.revokeByIdAndApplicationId(hash, app);
        cache.touchUser(access.meta().user());
        cache.forgetContextAfterCommit(hash);
        audit.audit(actor, "CONTEXT_REVOKED", "user:" + access.meta().u(), access.meta().department(), app, "");
    }

    private Access resolve(long app, String token) {
        String hash = Credentials.hashOrNull(token);
        if (hash == null) {
            throw ApiException.contextInvalid();
        }
        PermissionCache.Lookup lookup = cache.context(hash);
        Meta cachedMeta = lookup.meta() == null ? null : read(lookup.meta(), Meta.class);
        if (lookup.status() == PermissionCache.Status.HIT && cachedMeta != null) {
            Payload payload = read(lookup.payload(), Payload.class);
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
        Access loaded = snapshot.execute(status -> load(hash));
        String storeKey = lookup.status() == PermissionCache.Status.MISS ? lookup.storeKey() : null;
        if (loaded == null) {
            cache.store(storeKey, write(Payload.INVALID), PermissionCache.ENTRY_TTL_SECONDS);
            throw ApiException.contextInvalid();
        }
        long now = Instant.now().getEpochSecond();
        long remaining = loaded.meta().exp() - now;
        if (lookup.status() == PermissionCache.Status.NONE) {
            cache.putContext(hash, write(loaded.meta()), remaining);
        }
        long ttl = Math.min(PermissionCache.ENTRY_TTL_SECONDS, remaining);
        if (loaded.payload().refreshAt() != null) {
            ttl = Math.min(ttl, loaded.payload().refreshAt() - now);
        }
        cache.store(storeKey, write(loaded.payload()), ttl);
        if (!loaded.meta().usableFor(app) || !loaded.payload().valid()) {
            throw ApiException.contextInvalid();
        }
        return loaded;
    }

    private Access load(String hash) {
        Optional<PermissionContextRepository.ContextState> found = contexts.findStateById(hash);
        if (found.isEmpty()) {
            return null;
        }
        PermissionContextRepository.ContextState row = found.get();
        if (row.getDepartmentId() == null) {
            return null;
        }
        Meta meta = new Meta(String.valueOf(row.getUserId()), String.valueOf(row.getDepartmentId()),
                String.valueOf(row.getApplicationId()), row.getContextVersion(), row.getExpiresAt().getEpochSecond());
        return new Access(meta, row.isValid() ? compute(hash, meta) : Payload.INVALID);
    }

    private Payload compute(String hash, Meta meta) {
        List<Long> activated = contextRoles.findRoleIdsByContextId(hash);
        if (activated.isEmpty()) {
            return new Payload(true, List.of(), List.of(), null);
        }
        List<RoleRepository.EffectiveRole> directActive =
                roles.findEffectiveRoles(meta.user(), activated, meta.department(), meta.application());
        if (directActive.isEmpty()) {
            return new Payload(true, List.of(), List.of(), null);
        }
        List<ContextRoleResult> effectiveRoles = new ArrayList<>(directActive.size());
        List<Long> activeRootIds = new ArrayList<>(directActive.size());
        Long refreshAt = null;
        for (RoleRepository.EffectiveRole r : directActive) {
            effectiveRoles.add(new ContextRoleResult(r.getId(), r.getName(), r.getCode()));
            activeRootIds.add(r.getId());
            if (r.getExpiresAt() != null) {
                long at = r.getExpiresAt().getEpochSecond();
                if (refreshAt == null || at < refreshAt) {
                    refreshAt = at;
                }
            }
        }
        List<Long> expanded = roleInheritances.expandActiveRoleIds(activeRootIds, meta.department(), meta.application());
        List<String> effectivePermissions = expanded.isEmpty()
                ? List.of()
                : rolePermissions.findActivePermissionCodesInDomain(expanded, meta.department(), meta.application());
        return new Payload(true, effectiveRoles, effectivePermissions, refreshAt);
    }

    private static ContextResult contextResult(Access access, String token) {
        Meta meta = access.meta();
        return new ContextResult(token, meta.user(), meta.department(), meta.application(), meta.v(),
                Instant.ofEpochSecond(meta.exp()), access.payload().roles(), access.payload().permissions());
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException | RuntimeException e) {
            return null;
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
