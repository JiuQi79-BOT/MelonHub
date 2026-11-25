package com.melonhub.rbac.service.ddd.application;

import com.melonhub.rbac.dao.Visibility;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate;
import com.melonhub.rbac.service.ddd.domain.model.UserMembershipAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.GovernanceRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.IdentityRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PermissionContextRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CacheGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CryptoGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.DomainEventPublisher;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.TransactionRunner;
import com.melonhub.rbac.service.ddd.domain.repository.RbacRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.GrantDuration;
import com.melonhub.rbac.service.ddd.domain.value.IsolationDomain;
import com.melonhub.rbac.service.port.RbacUseCase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DDD 应用服务：部门、软件、API Key、用户与部门归属、隔离域角色/权限/成员授权、跨部门复制与权限诊断。
 * 业务不变量分别委托给 {@link UserMembershipAggregate}、{@link DomainAuthorizationAggregate} 与 {@link GrantDuration}。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class DddRbacService implements RbacUseCase {
    private static final int MAX_ACTIVE_KEYS = 10;
    private static final int MEMBER_ATTEMPTS = 3;

    private final RbacRepositoryPort repository;
    private final PermissionContextRepositoryPort contexts;
    private final GovernanceRepositoryPort governance;
    private final IdentityRepositoryPort identity;
    private final CacheGateway cache;
    private final DomainEventPublisher events;
    private final CryptoGateway crypto;
    private final TransactionRunner transactions;

    public DddRbacService(RbacRepositoryPort repository, PermissionContextRepositoryPort contexts,
                          GovernanceRepositoryPort governance, IdentityRepositoryPort identity,
                          CacheGateway cache, DomainEventPublisher events,
                          CryptoGateway crypto, TransactionRunner transactions) {
        this.repository = repository;
        this.contexts = contexts;
        this.governance = governance;
        this.identity = identity;
        this.cache = cache;
        this.events = events;
        this.crypto = crypto;
        this.transactions = transactions;
    }

    @Override
    public Map<String, Object> health() {
        boolean database;
        try {
            database = repository.healthy();
        } catch (RuntimeException e) {
            database = false;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("status", database ? "UP" : "DOWN");
        result.put("database", database ? "UP" : "DOWN");
        result.put("cache", cache.health());
        result.put("model", "RBAC1");
        return result;
    }

    @Override
    public void audit(String actor, String action, String target, Long department, Long app, String detail) {
        repository.recordAudit(actor, action, target, department, app, detail, crypto.currentClientAddress());
    }

    @Override
    public void domain(long dept, long app) {
        DddBoundary.run(() -> {
            if (!repository.departmentExists(dept)) throw DomainException.missing("部门不存在");
            if (!repository.applicationExists(app)) throw DomainException.missing("软件不存在");
        });
    }

    @Override
    public void scoped(String table, long id, long dept, long app) {
        DddBoundary.run(() -> {
            if (!repository.scopedExists(table, id, dept, app)) {
                throw DomainException.forbidden("关联记录不属于当前隔离域");
            }
        });
    }

    // ---- departments ----
    @Override
    public List<Map<String, Object>> departments(Collection<Long> visible) {
        return repository.departments(visible);
    }

    @Override
    @Transactional
    public long department(Requests.Department body, Long id, String actor) {
        return DddBoundary.execute(() -> {
            long deptId;
            if (id == null) {
                deptId = repository.createDepartment(body.name(), body.code());
                audit(actor, "DEPARTMENT_CREATED", body.name(), deptId, null, "编码 " + body.code());
            } else {
                if (repository.updateDepartment(id, body.name(), body.code()) == 0) {
                    throw DomainException.missing("部门不存在");
                }
                deptId = id;
                audit(actor, "DEPARTMENT_UPDATED", body.name(), deptId, null, "编码 " + body.code());
            }
            return deptId;
        });
    }

    @Override
    @Transactional
    public void deleteDepartment(long id, String actor) {
        DddBoundary.run(() -> {
            String[] label = repository.entityLabel("departments", id)
                    .orElseThrow(() -> DomainException.missing("部门不存在"));
            DomainAuthorizationAggregate.assertDepartmentDeletable(repository.departmentUsage(id));
            contexts.revokeDepartment(id);
            repository.deleteDepartment(id);
            audit(actor, "DEPARTMENT_DELETED", label[0], null, null, "编码 " + label[1]);
        });
    }

    // ---- applications and keys ----
    @Override
    public List<Map<String, Object>> applications(Collection<Long> visible) {
        return repository.applications(visible);
    }

    @Override
    @Transactional
    public Map<String, Object> application(Requests.Application body, String actor) {
        long id = repository.createApplication(body.name(), body.code(), text(body.description()), text(body.owner()));
        var key = issueKey(id, "默认密钥", null, actor);
        audit(actor, "APPLICATION_CREATED", body.name(), null, id, "编码 " + body.code() + "，已生成默认密钥 " + key.get("prefix"));
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("apiKey", key.get("apiKey"));
        result.put("keyId", key.get("id"));
        return result;
    }

    @Override
    @Transactional
    public long updateApplication(long id, Requests.Application body, String actor) {
        return DddBoundary.execute(() -> {
            if (repository.updateApplication(id, body.name(), body.code(), text(body.description()), text(body.owner())) == 0) {
                throw DomainException.missing("软件不存在");
            }
            audit(actor, "APPLICATION_UPDATED", body.name(), null, id, "编码 " + body.code());
            return id;
        });
    }

    @Override
    @Transactional
    public void applicationStatus(long id, boolean enabled, String actor) {
        DddBoundary.run(() -> {
            if (repository.updateApplicationStatus(id, enabled) == 0) throw DomainException.missing("软件不存在");
            if (!enabled) contexts.revokeApplication(id);
            cache.touchApplication(id);
            events.emit(id, enabled ? "application.enabled" : "application.disabled", Map.of());
            audit(actor, enabled ? "APPLICATION_ENABLED" : "APPLICATION_DISABLED", "application:" + id, null, id,
                    enabled ? "" : "已清除该软件全部权限上下文");
        });
    }

    @Override
    @Transactional
    public void deleteApplication(long id, String actor) {
        DddBoundary.run(() -> {
            String[] label = repository.entityLabel("applications", id)
                    .orElseThrow(() -> DomainException.missing("软件不存在"));
            DomainAuthorizationAggregate.assertApplicationDeletable(repository.applicationUsage(id));
            contexts.revokeApplication(id);
            cache.touchApplication(id);
            repository.deleteApplication(id);
            audit(actor, "APPLICATION_DELETED", label[0], null, id, "编码 " + label[1]);
        });
    }

    @Override
    public List<Map<String, Object>> keys(long app) {
        return DddBoundary.execute(() -> {
            if (!repository.applicationExists(app)) throw DomainException.missing("软件不存在");
            return repository.applicationKeys(app);
        });
    }

    @Override
    @Transactional
    public Map<String, Object> createKey(long app, Requests.ApiKey body, String actor) {
        return DddBoundary.execute(() -> {
            if (!repository.lockApplication(app)) throw DomainException.missing("软件不存在");
            if (repository.lockedActiveKeyCount(app) >= MAX_ACTIVE_KEYS) {
                throw DomainException.conflict("每个软件最多保留 " + MAX_ACTIVE_KEYS + " 个有效密钥，请先吊销不用的密钥");
            }
            var key = issueKey(app, body.name().strip(), body.expiresInDays(), actor);
            audit(actor, "API_KEY_CREATED", body.name(), null, app,
                    key.get("prefix") + (body.expiresInDays() == null ? "，永不过期" : "，" + body.expiresInDays() + " 天后过期"));
            return key;
        });
    }

    @Override
    @Transactional
    public void revokeKey(long app, long key, String actor) {
        DddBoundary.run(() -> {
            String name = repository.applicationKeyName(app, key).orElseThrow(() -> DomainException.missing("密钥不存在"));
            if (repository.revokeApplicationKey(app, key) == 0) throw DomainException.conflict("密钥已被吊销");
            cache.touchKeys(app);
            events.emit(app, "apikey.revoked", Map.of("keyId", key, "name", name));
            audit(actor, "API_KEY_REVOKED", name, null, app, "key:" + key);
        });
    }

    @Override
    @Transactional
    public String rotateKey(long app, String actor) {
        return DddBoundary.execute(() -> {
            if (!repository.lockApplication(app)) throw DomainException.missing("软件不存在");
            int revoked = repository.revokeAllApplicationKeys(app);
            var key = issueKey(app, "轮换密钥 " + LocalDate.now(ZONE), null, actor);
            cache.touchKeys(app);
            events.emit(app, "apikey.revoked", Map.of("rotated", true, "revoked", revoked));
            audit(actor, "API_KEY_ROTATED", "application:" + app, null, app,
                    "吊销 " + revoked + " 个旧密钥，新密钥 " + key.get("prefix"));
            return (String) key.get("apiKey");
        });
    }

    private Map<String, Object> issueKey(long app, String name, Integer days, String actor) {
        String key = crypto.generateApiKey();
        String prefix = crypto.apiKeyPrefix(key);
        Instant expires = days == null ? null : Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(days, ChronoUnit.DAYS);
        long id = repository.createApplicationKey(app, name, prefix, crypto.hashToken(key), actor, expires);
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("name", name);
        result.put("prefix", prefix);
        result.put("apiKey", key);
        result.put("expiresAt", expires);
        return result;
    }

    // ---- users ----
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> users(String keyword, Long department, Boolean enabled, Collection<Long> visibleDepartments,
                                     int page, int size) {
        var result = repository.users(keyword, department, enabled, visibleDepartments, page, size);
        attach(result.items(), repository.memberships(ids(result.items())), "user_id", "department_id", "departmentIds");
        return RbacUseCase.page(result.items(), result.total(), page, size);
    }

    @Override
    public List<Map<String, Object>> departmentUsers(long dept) {
        return DddBoundary.execute(() -> {
            if (!repository.departmentExists(dept)) throw DomainException.missing("部门不存在");
            return repository.departmentUsers(dept);
        });
    }

    @Override
    @Transactional
    public long user(Requests.User body, Long id, String actor) {
        return user(body, id, actor, null);
    }

    @Override
    @Transactional
    public long user(Requests.User body, Long id, String actor, Collection<Long> scope) {
        return DddBoundary.execute(() -> {
            var departments = new LinkedHashSet<>(body.departmentIds());
            if (id == null) {
                UserMembershipAggregate.assertCreateDepartmentsInScope(departments, scope);
            }
            for (long dept : departments) {
                if (!repository.departmentExists(dept)) throw DomainException.missing("部门不存在");
            }
            String email = text(body.email());
            String label = body.name() + " (" + body.externalId() + ")";
            if (id == null) {
                long createdId = repository.createUser(body.name(), body.externalId(), email);
                for (long dept : departments) repository.addMembership(createdId, dept);
                audit(actor, "USER_CREATED", label, null, null, "所属部门 " + departments.size() + " 个");
                return createdId;
            }
            String previousExternal = repository.lockUser(id).orElseThrow(() -> DomainException.missing("用户不存在"));
            var currentDepartments = repository.lockedUserDepartments(id);
            var existingRow = repository.user(id).orElseThrow(() -> DomainException.missing("用户不存在"));
            var aggregate = new UserMembershipAggregate(id, (String) existingRow.get("name"), previousExternal,
                    (String) existingRow.get("email"), currentDepartments);
            var removedPreview = currentDepartments.stream().filter(d -> !departments.contains(d)).toList();
            var staleAssignments = repository.userRoleAssignmentsInDepartments(id, removedPreview);
            var plan = aggregate.planUpdate(body.name(), body.externalId(), body.email(), departments, scope, staleAssignments);

            repository.updateUser(id, body.name(), body.externalId(), plan.normalizedEmail(), plan.externalIdChanged());
            if (plan.externalIdChanged()) {
                identity.renameCredential(id, body.externalId());
                identity.revokeUserSessions(id);
            }
            repository.removeUserRolesInDepartments(id, plan.removedDepartments());
            for (long dept : plan.removedDepartments()) repository.removeMembership(id, dept);
            for (long dept : plan.finalDepartments()) repository.addMembership(id, dept);
            repository.clearInactiveDepartment(id);
            cache.touchUser(id);
            events.emitAll("user.updated", Map.of("userId", id, "externalId", body.externalId()));
            for (var rev : plan.staleRevocations()) {
                long dept = rev.domain().departmentId();
                long app = rev.domain().applicationId();
                contexts.revokeDomain(id, dept, app);
                events.emit(app, "user.roles.changed", Map.of("userId", id, "externalId", body.externalId(),
                        "departmentId", dept, "removed", rev.roleIds().size()));
                audit(actor, "USER_ROLES_UPDATED", body.externalId(), dept, app,
                        "移除角色 " + String.join(", ", rev.roleCodes()));
            }
            audit(actor, "USER_UPDATED", label, null, null, plan.auditDetail(previousExternal));
            return id;
        });
    }

    @Override
    @Transactional
    public void userStatus(long id, boolean enabled, String actor) {
        userStatus(id, enabled, actor, null);
    }

    @Override
    @Transactional
    public void userStatus(long id, boolean enabled, String actor, Collection<Long> scope) {
        DddBoundary.run(() -> {
            String external = lockOwnedUser(id, scope);
            repository.updateUserStatus(id, enabled);
            if (!enabled) identity.revokeUserSessions(id);
            cache.touchUser(id);
            events.emitAll(enabled ? "user.enabled" : "user.disabled", Map.of("userId", id, "externalId", external));
            audit(actor, enabled ? "USER_ENABLED" : "USER_DISABLED", external, null, null, enabled ? "" : "原有上下文已失效");
        });
    }

    @Override
    @Transactional
    public void deleteUser(long id, String actor, Collection<Long> scope) {
        DddBoundary.run(() -> {
            String external = lockOwnedUser(id, scope);
            int cancelled = governance.cancelPendingByColumn("user_id", id, "用户已删除");
            repository.deleteUser(id);
            cache.touchUser(id);
            events.emitAll("user.deleted", Map.of("userId", id, "externalId", external));
            audit(actor, "USER_DELETED", external, null, null, cancelled == 0 ? "" : "撤回待审批申请 " + cancelled + " 个");
        });
    }

    @Override
    public String lockOwnedUser(long id, Collection<Long> scope) {
        return DddBoundary.execute(() -> {
            String external = repository.lockUser(id).orElseThrow(() -> DomainException.missing("用户不存在"));
            var aggregate = new UserMembershipAggregate(id, "", external, "", repository.lockedUserDepartments(id));
            aggregate.assertFullyOwnedBy(scope);
            return external;
        });
    }

    @Override
    public Map<String, Object> userAccess(long id, Visibility visible) {
        return DddBoundary.execute(() -> {
            var user = repository.user(id).orElseThrow(() -> DomainException.missing("用户不存在"));
            var memberOf = repository.userDepartments(id);
            if (visible.departments() != null && memberOf.stream().noneMatch(visible::department)) {
                throw DomainException.forbidden("该用户不在你管理的部门中");
            }
            user.put("departmentIds", memberOf);
            var domains = new LinkedHashMap<String, Map<String, Object>>();
            for (var row : repository.accessRoles(id)) {
                long app = number(row.get("application_id"));
                if (!visible.application(app) || !visible.department(number(row.get("department_id")))) continue;
                var domain = domains.computeIfAbsent(row.get("department_id") + ":" + app, k -> {
                    var d = new LinkedHashMap<String, Object>();
                    d.put("departmentId", number(row.get("department_id")));
                    d.put("departmentName", row.get("department_name"));
                    d.put("applicationId", app);
                    d.put("applicationName", row.get("application_name"));
                    d.put("applicationEnabled", row.get("application_enabled"));
                    d.put("roles", new ArrayList<Map<String, Object>>());
                    d.put("permissions", new ArrayList<String>());
                    return d;
                });
                var role = new LinkedHashMap<String, Object>();
                role.put("id", number(row.get("id")));
                role.put("name", row.get("name"));
                role.put("code", row.get("code"));
                role.put("enabled", row.get("enabled"));
                role.put("expiresAt", row.get("expires_at"));
                role.put("grantedBy", row.get("granted_by"));
                rows(domain.get("roles")).add(role);
            }
            for (var row : repository.accessPermissions(id)) {
                var domain = domains.get(row.get("department_id") + ":" + number(row.get("application_id")));
                if (domain != null) strings(domain.get("permissions")).add((String) row.get("code"));
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("user", user);
            result.put("domains", new ArrayList<>(domains.values()));
            result.put("contexts", contexts.activeContexts(id).stream()
                    .filter(c -> visible.application(number(c.get("applicationId"))) && visible.department(number(c.get("departmentId"))))
                    .toList());
            return result;
        });
    }

    @Override
    @Transactional
    public int revokeUserContexts(long id, String actor, Collection<Long> scope) {
        return DddBoundary.execute(() -> {
            String external = lockOwnedUser(id, scope);
            repository.invalidateUser(id);
            int revoked = contexts.revokeUser(id);
            cache.touchUser(id);
            events.emitAll("user.contexts.revoked", Map.of("userId", id, "externalId", external));
            audit(actor, "USER_CONTEXTS_REVOKED", external, null, null, "强制下线 " + revoked + " 个上下文");
            return revoked;
        });
    }

    // ---- roles and permissions in one isolation domain ----
    @Override
    public List<Map<String, Object>> roles(long dept, long app) {
        domain(dept, app);
        var roles = repository.roles(dept, app);
        attach(roles, repository.domainRolePermissionPairs(dept, app), "role_id", "permission_id", "permissionIds");
        return roles;
    }

    @Override
    @Transactional
    public void roleParents(long role, long dept, long app, List<Long> parents, String actor) {
        DddBoundary.run(() -> {
            scoped("roles", role, dept, app);
            var wanted = new LinkedHashSet<>(parents);
            if (wanted.contains(role) || repository.scopedCount("roles", wanted, dept, app) != wanted.size())
                throw DomainException.forbidden("继承角色必须属于当前隔离域且不能包含自身");
            for (long parent : wanted) {
                if (repository.inheritedRoleIds(List.of(parent), dept, app).contains(role))
                    throw DomainException.conflict("角色继承关系不能形成循环");
            }
            if (!repository.lockRole(role)) throw DomainException.missing("角色不存在");
            repository.replaceRoleParents(role, dept, app, wanted);
            cache.touchDomain(dept, app);
            events.emit(app, "role.inheritance.changed", Map.of("roleId", role, "departmentId", dept));
            audit(actor, "ROLE_INHERITANCE_UPDATED", singleCode("roles", role), dept, app, "继承角色 " + wanted.size() + " 个");
        });
    }

    @Override
    @Transactional
    public long role(Requests.Role body, Long id, long dept, long app, String actor) {
        return DddBoundary.execute(() -> {
            domain(dept, app);
            String description = text(body.description());
            if (id == null) {
                long roleId = repository.createRole(dept, app, body.name(), body.code(), description);
                audit(actor, "ROLE_CREATED", body.name() + " (" + body.code() + ")", dept, app);
                return roleId;
            }
            scoped("roles", id, dept, app);
            repository.updateRole(id, body.name(), body.code(), description);
            cache.touchDomain(dept, app);
            audit(actor, "ROLE_UPDATED", body.name() + " (" + body.code() + ")", dept, app);
            return id;
        });
    }

    @Override
    @Transactional
    public void roleStatus(long id, long dept, long app, boolean enabled, String actor) {
        DddBoundary.run(() -> {
            scoped("roles", id, dept, app);
            repository.updateRoleStatus(id, enabled);
            cache.touchDomain(dept, app);
            events.emit(app, enabled ? "role.enabled" : "role.disabled", Map.of("roleId", id, "departmentId", dept));
            audit(actor, enabled ? "ROLE_ENABLED" : "ROLE_DISABLED", singleCode("roles", id), dept, app);
        });
    }

    @Override
    @Transactional
    public void deleteRole(long id, long dept, long app, String actor) {
        DddBoundary.run(() -> {
            scoped("roles", id, dept, app);
            String code = singleCode("roles", id);
            int cancelled = governance.cancelPendingByColumn("role_id", id, "角色已删除");
            repository.deleteRole(id);
            cache.touchDomain(dept, app);
            events.emit(app, "role.deleted", Map.of("roleId", id, "departmentId", dept));
            audit(actor, "ROLE_DELETED", code, dept, app, cancelled == 0 ? "" : "撤回待审批申请 " + cancelled + " 个");
        });
    }

    @Override
    public List<Map<String, Object>> permissions(long dept, long app) {
        domain(dept, app);
        return repository.permissions(dept, app);
    }

    @Override
    @Transactional
    public long permission(Requests.Permission body, Long id, long dept, long app, String actor) {
        return DddBoundary.execute(() -> {
            domain(dept, app);
            if (id == null) {
                long permId = repository.createPermission(dept, app, body.name(), body.code(), body.resource(), body.action());
                audit(actor, "PERMISSION_CREATED", body.name() + " (" + body.code() + ")", dept, app);
                return permId;
            }
            scoped("permissions", id, dept, app);
            repository.updatePermission(id, body.name(), body.code(), body.resource(), body.action());
            cache.touchDomain(dept, app);
            events.emit(app, "permission.updated", Map.of("permissionId", id, "departmentId", dept));
            audit(actor, "PERMISSION_UPDATED", body.name() + " (" + body.code() + ")", dept, app);
            return id;
        });
    }

    @Override
    @Transactional
    public void deletePermission(long id, long dept, long app, String actor) {
        DddBoundary.run(() -> {
            scoped("permissions", id, dept, app);
            String code = singleCode("permissions", id);
            repository.deletePermission(id);
            cache.touchDomain(dept, app);
            events.emit(app, "permission.deleted", Map.of("permissionId", id, "departmentId", dept));
            audit(actor, "PERMISSION_DELETED", code, dept, app);
        });
    }

    @Override
    @Transactional
    public void rolePermissions(long role, long dept, long app, List<Long> permissions, String actor) {
        DddBoundary.run(() -> {
            scoped("roles", role, dept, app);
            var wanted = new LinkedHashSet<>(permissions);
            if (repository.scopedCount("permissions", wanted, dept, app) != wanted.size()) {
                throw DomainException.forbidden("关联记录不属于当前隔离域");
            }
            if (!repository.lockRole(role)) throw DomainException.missing("角色不存在");
            var before = new HashSet<>(repository.lockedRolePermissionIds(role));
            repository.replaceRolePermissions(role, dept, app, wanted);
            cache.touchDomain(dept, app);
            events.emit(app, "role.permissions.changed", Map.of("roleId", role, "departmentId", dept));
            audit(actor, "ROLE_PERMISSIONS_UPDATED", singleCode("roles", role), dept, app, diff("permissions", before, wanted));
        });
    }

    @Override
    public List<Map<String, Object>> roleMembers(long role, long dept, long app) {
        scoped("roles", role, dept, app);
        return repository.roleMembers(role);
    }

    private static final class LateMembersSignal extends RuntimeException {
        final Set<Long> users;
        LateMembersSignal(Set<Long> users) { super(null, null, false, false); this.users = users; }
    }

    @Override
    public void roleMembers(long role, long dept, long app, List<Long> users, String actor) {
        DddBoundary.run(() -> {
            var knownLate = new TreeSet<Long>();
            for (int attempt = 1; ; attempt++) {
                try {
                    transactions.inTransaction(() -> {
                        replaceRoleMembersAttempt(role, dept, app, users, knownLate, actor);
                        return null;
                    });
                    return;
                } catch (LateMembersSignal e) {
                    if (attempt >= MEMBER_ATTEMPTS) {
                        throw DomainException.busy("SERVICE_BUSY", "角色成员正在被同时修改，请稍后重试");
                    }
                    knownLate.addAll(e.users);
                }
            }
        });
    }

    private void replaceRoleMembersAttempt(long role, long dept, long app, List<Long> users, Set<Long> knownLate, String actor) {
        scoped("roles", role, dept, app);
        var aggregate = new DomainAuthorizationAggregate(new IsolationDomain(dept, app));
        var wanted = new LinkedHashSet<>(users);
        var affected = new TreeSet<Long>(wanted);
        affected.addAll(knownLate);
        affected.addAll(repository.roleMemberIds(role));
        if (!repository.lockUsers(affected).containsAll(wanted)) throw DomainException.missing("用户不存在");
        if (!repository.lockRole(role)) throw DomainException.missing("角色不存在");
        var before = new LinkedHashSet<>(repository.lockedRoleMemberIds(role));
        var plan = aggregate.planRoleMemberReplacement(wanted, affected, before);
        if (plan.requiresRetry()) throw new LateMembersSignal(plan.lateMembers());
        if (repository.lockedMembersCount(wanted, dept) != wanted.size()) {
            throw DomainException.forbidden("用户不属于当前部门");
        }
        repository.removeRoleMembers(role, plan.removed());
        repository.addRoleMembers(role, dept, app, plan.added(), actor);
        var changed = new ArrayList<>(plan.added());
        changed.addAll(plan.removed());
        contexts.revokeDomainForUsers(changed, dept, app);
        cache.touchDomain(dept, app);
        events.emit(app, "user.roles.changed", Map.of("roleId", role, "departmentId", dept,
                "added", plan.added().size(), "removed", plan.removed().size()));
        String roleCode = singleCode("roles", role);
        for (long user : plan.added()) audit(actor, "ROLE_MEMBERS_UPDATED", "user:" + user, dept, app, "新增角色 " + roleCode);
        for (long user : plan.removed()) audit(actor, "ROLE_MEMBERS_UPDATED", "user:" + user, dept, app, "移除角色 " + roleCode);
    }

    @Override
    public Map<String, Object> assignments(long user, long dept, long app) {
        domain(dept, app);
        var current = repository.assignmentExpiryViews(user, dept, app);
        var timed = new LinkedHashMap<Long, Object>();
        current.forEach((role, at) -> { if (at != null) timed.put(role, at); });
        return Map.of("roleIds", List.copyOf(current.keySet()), "roleExpiries", timed);
    }

    @Override
    public Map<String, Object> domainAssignments(long dept, long app, String keyword, int page, int size) {
        domain(dept, app);
        var result = repository.departmentMembers(dept, keyword, page, size);
        var pairs = repository.domainRolePairs(dept, app, ids(result.items()));
        attach(result.items(), pairs, "user_id", "role_id", "roleIds");
        var expiries = new HashMap<Long, Map<Long, Object>>();
        for (var pair : pairs) {
            if (pair.get("expires_at") != null) {
                expiries.computeIfAbsent(number(pair.get("user_id")), k -> new LinkedHashMap<>())
                        .put(number(pair.get("role_id")), pair.get("expires_at"));
            }
        }
        for (var item : result.items()) item.put("roleExpiries", expiries.getOrDefault(number(item.get("id")), Map.of()));
        return RbacUseCase.page(result.items(), result.total(), page, size);
    }

    @Override
    @Transactional
    public Map<String, Object> addUserRoles(long dept, long app, Requests.BatchUserRoles body, String actor) {
        return DddBoundary.execute(() -> {
            domain(dept, app);
            var users = new TreeSet<>(body.userIds());
            var roles = new TreeSet<>(body.roleIds());
            if (repository.lockUsers(users).size() != users.size()) throw DomainException.missing("用户不存在");
            if (repository.lockedMembersCount(users, dept) != users.size()) throw DomainException.forbidden("用户不属于当前部门");
            for (long role : roles) {
                if (!repository.lockRole(role)) throw DomainException.missing("角色不存在");
                scoped("roles", role, dept, app);
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            var currentByUser = new LinkedHashMap<Long, Map<Long, Instant>>();
            for (long user : users) currentByUser.put(user, repository.lockedAssignmentExpiries(user, dept, app));
            var aggregate = new DomainAuthorizationAggregate(new IsolationDomain(dept, app));
            var plan = aggregate.planBatchRoleAdditions(users, roles, currentByUser, body.expiresInDays(), now);
            for (var entry : plan.rolesToAddByUser().entrySet()) {
                long user = entry.getKey();
                for (long role : entry.getValue()) {
                    repository.grantRole(user, dept, app, role, plan.sharedExpiry(), actor);
                }
                String external = (String) repository.user(user).orElseThrow().get("externalId");
                contexts.revokeDomain(user, dept, app);
                cache.touchUser(user);
                events.emit(app, "user.roles.changed", Map.of("userId", user, "externalId", external, "departmentId", dept));
                audit(actor, "USER_ROLES_UPDATED", external, dept, app,
                        "批量追加 " + String.join(", ", repository.codes("roles", entry.getValue())));
            }
            return Map.of("users", users.size(), "changedUsers", plan.changedUsers(), "grantsAdded", plan.grantsAdded());
        });
    }

    @Override
    @Transactional
    public void userRoles(long user, long dept, long app, List<Long> roles, String actor) {
        userRoles(user, dept, app, roles, null, null, actor);
    }

    @Override
    @Transactional
    public void userRoles(long user, long dept, long app, List<Long> roles, Integer expiresInDays, String actor) {
        userRoles(user, dept, app, roles, expiresInDays, null, actor);
    }

    @Override
    @Transactional
    public void userRoles(long user, long dept, long app, List<Long> roles, Integer expiresInDays,
                          List<Requests.RoleExpiry> expiries, String actor) {
        DddBoundary.run(() -> {
            domain(dept, app);
            String external = repository.lockUser(user).orElseThrow(() -> DomainException.missing("用户不存在"));
            if (!repository.isLockedMember(user, dept)) throw DomainException.forbidden("用户不属于当前部门");
            var wanted = new LinkedHashSet<>(roles);
            if (repository.scopedCount("roles", wanted, dept, app) != wanted.size()) {
                throw DomainException.forbidden("关联记录不属于当前隔离域");
            }
            var overrides = expiries == null ? List.<GrantDuration.RoleExpiryOverride>of()
                    : expiries.stream()
                            .map(e -> new GrantDuration.RoleExpiryOverride(e.roleId(),
                                    e.expiresAt() == null ? null : e.expiresAt().toInstant()))
                            .toList();
            var current = repository.lockedAssignmentExpiries(user, dept, app);
            var aggregate = new DomainAuthorizationAggregate(new IsolationDomain(dept, app));
            var plan = aggregate.planUserRoleReplacement(wanted, current, expiresInDays, overrides, Instant.now());

            repository.removeUserRoles(user, plan.removedRoleIds());
            for (var change : plan.grantsToApply()) {
                repository.grantRole(user, dept, app, change.roleId(), change.expiresAt(), actor);
            }
            contexts.revokeDomain(user, dept, app);
            cache.touchUser(user);
            events.emit(app, "user.roles.changed", Map.of("userId", user, "externalId", external, "departmentId", dept));
            String detail = diff("roles", current.keySet(), plan.finalRoleIds());
            if (plan.expiryChanges() > 0) detail += "；调整到期时间 " + plan.expiryChanges() + " 个";
            if (expiresInDays != null) detail += "，" + expiresInDays + " 天后到期";
            audit(actor, "USER_ROLES_UPDATED", external, dept, app, detail);
        });
    }

    @Override
    @Transactional
    public void applicationRateLimit(long id, Integer perSecond, String actor) {
        DddBoundary.run(() -> {
            if (repository.updateApplicationRateLimit(id, perSecond) == 0) throw DomainException.missing("软件不存在");
            audit(actor, "APPLICATION_RATE_LIMIT_UPDATED", "application:" + id, null, id,
                    perSecond == null ? "恢复平台默认" : perSecond == 0 ? "不限制" : perSecond + " 次/秒");
        });
    }

    @Override
    @Transactional
    public Map<String, Object> copyDomain(Requests.DomainCopy body, String actor) {
        return DddBoundary.execute(() -> {
            long app = body.applicationId(), source = body.sourceDepartmentId(), target = body.targetDepartmentId();
            if (source == target) throw DomainException.bad("源部门与目标部门不能相同");
            domain(source, app);
            domain(target, app);
            var sourcePerms = toPermissionSpecs(repository.permissions(source, app));
            var targetPerms = toPermissionSpecs(repository.permissions(target, app));
            var sourceRoles = toRoleSpecs(repository.roles(source, app));
            var targetRoles = toRoleSpecs(repository.roles(target, app));
            var grants = new HashMap<Long, List<Long>>();
            for (var pair : repository.domainRolePermissionPairs(source, app)) {
                grants.computeIfAbsent(number(pair.get("role_id")), k -> new ArrayList<>())
                        .add(number(pair.get("permission_id")));
            }
            var aggregate = new DomainAuthorizationAggregate(new IsolationDomain(source, app));
            var plan = aggregate.planCopyTo(target, sourcePerms, targetPerms, sourceRoles, targetRoles, grants);

            var permissionMap = new HashMap<>(plan.existingPermissionMap());
            for (var p : plan.permissionsToCreate()) {
                long created = repository.createPermission(target, app, p.name(), p.code(), p.resource(), p.action());
                permissionMap.put(p.id(), created);
            }
            for (var roleCopy : plan.rolesToCreate()) {
                var r = roleCopy.sourceRole();
                long createdRole = repository.createRole(target, app, r.name(), r.code(), text(r.description()), r.enabled());
                var mappedPermissions = new LinkedHashSet<Long>();
                for (long oldPermId : roleCopy.sourcePermissionIds()) {
                    Long mapped = permissionMap.get(oldPermId);
                    if (mapped != null) mappedPermissions.add(mapped);
                }
                repository.replaceRolePermissions(createdRole, target, app, mappedPermissions);
            }
            int permissionsCreated = plan.permissionsToCreate().size();
            int rolesCreated = plan.rolesToCreate().size();
            audit(actor, "DOMAIN_COPIED", "department:" + source + " -> department:" + target, target, app,
                    "新建权限 " + permissionsCreated + " 个、角色 " + rolesCreated + " 个；同编码跳过权限 "
                            + plan.permissionsSkipped() + " 个、角色 " + plan.rolesSkipped() + " 个");
            var result = new LinkedHashMap<String, Object>();
            result.put("permissionsCreated", permissionsCreated);
            result.put("permissionsSkipped", plan.permissionsSkipped());
            result.put("rolesCreated", rolesCreated);
            result.put("rolesSkipped", plan.rolesSkipped());
            return result;
        });
    }

    @Override
    public Map<String, Object> simulate(Requests.Simulation body) {
        return DddBoundary.execute(() -> {
            long dept = body.departmentId(), app = body.applicationId();
            domain(dept, app);
            var user = repository.user(body.userId()).orElseThrow(() -> DomainException.missing("用户不存在"));
            boolean appEnabled = repository.applicationEnabled(app);
            boolean member = repository.isMember(body.userId(), dept);
            var assigned = repository.assignedRolesInDomain(body.userId(), dept, app);
            var aggregate = new DomainAuthorizationAggregate(new IsolationDomain(dept, app));
            var assessment = aggregate.assessSimulationRoles(
                    Boolean.TRUE.equals(user.get("enabled")), appEnabled, member,
                    assigned, body.activeRoleIds(), Instant.now());
            var grants = repository.permissionGrantsTyped(assessment.activeRoleIds());
            Object currentDept = user.get("activeDepartmentId");
            Long activeDeptId = currentDept == null ? null : number(currentDept);
            return aggregate.buildSimulationReport(user, activeDeptId, appEnabled, member,
                    assessment, grants, body.permissions());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> events(Long dept, Long app, Visibility visible, String actor, String action, String keyword,
                                      LocalDate from, LocalDate to, int page, int size) {
        Instant start = from == null ? null : from.atStartOfDay(ZONE).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toInstant();
        var query = new RbacRepositoryPort.AuditQuery(dept, app, DddBoundary.toDomainVisibility(visible),
                actor, action, keyword, start, end);
        var slice = repository.auditEvents(query, page, size);
        return RbacUseCase.page(slice.items(), slice.total(), page, size);
    }

    @Override
    public Map<String, Object> overview(long dept, long app) {
        domain(dept, app);
        return repository.domainOverview(dept, app);
    }

    private String singleCode(String table, long id) {
        var found = repository.codes(table, List.of(id));
        return found.isEmpty() ? "#" + id : found.get(0);
    }

    private String diff(String table, Set<Long> before, Set<Long> after) {
        var addedIds = after.stream().filter(id -> !before.contains(id)).toList();
        var removedIds = before.stream().filter(id -> !after.contains(id)).toList();
        return DomainAuthorizationAggregate.describeSetDiff(
                repository.codes(table, addedIds), repository.codes(table, removedIds));
    }

    private static List<DomainAuthorizationAggregate.PermissionSpec> toPermissionSpecs(List<Map<String, Object>> rows) {
        return rows.stream().map(p -> new DomainAuthorizationAggregate.PermissionSpec(
                number(p.get("id")), (String) p.get("name"), (String) p.get("code"),
                (String) p.get("resource"), (String) p.get("action"))).toList();
    }

    private static List<DomainAuthorizationAggregate.RoleSpec> toRoleSpecs(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> new DomainAuthorizationAggregate.RoleSpec(
                number(r.get("id")), (String) r.get("name"), (String) r.get("code"),
                (String) r.get("description"), Boolean.TRUE.equals(r.get("enabled")))).toList();
    }

    private static List<Long> ids(List<Map<String, Object>> items) {
        return items.stream().map(item -> number(item.get("id"))).toList();
    }

    private static void attach(List<Map<String, Object>> parents, List<Map<String, Object>> pairs,
                               String ownerKey, String childKey, String field) {
        var grouped = new HashMap<Long, List<Long>>();
        for (var pair : pairs) {
            grouped.computeIfAbsent(number(pair.get(ownerKey)), k -> new ArrayList<>()).add(number(pair.get(childKey)));
        }
        for (var parent : parents) parent.put(field, grouped.getOrDefault(number(parent.get("id")), List.of()));
    }

    private static long number(Object value) { return ((Number) value).longValue(); }
    private static String text(String value) { return Objects.requireNonNullElse(value, "").strip(); }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) { return (List<Map<String, Object>>) value; }
    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) { return (List<String>) value; }
}
