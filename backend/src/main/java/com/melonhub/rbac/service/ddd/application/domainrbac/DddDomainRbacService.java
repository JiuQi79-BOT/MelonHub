package com.melonhub.rbac.service.ddd.application.domainrbac;

import com.melonhub.rbac.service.ddd.application.shared.DddBoundary;
import com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.GovernanceRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PageSlice;
import com.melonhub.rbac.service.ddd.domain.repository.PermissionContextRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CacheGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.DomainEventPublisher;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.TransactionRunner;
import com.melonhub.rbac.service.ddd.domain.repository.RbacRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.GrantDuration;
import com.melonhub.rbac.service.ddd.domain.value.IsolationDomain;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.BatchUserRolesCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.BatchUserRolesResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainAssignmentResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainCopyCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainCopyResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.DomainOverviewResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.PermissionResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.RoleExpiryCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.RoleMemberResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.RoleResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SavePermissionCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SaveRoleCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SimulatedPermissionResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SimulatedRoleResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SimulationCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SimulationResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.UserRoleAssignmentsResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacUseCase;
import com.melonhub.rbac.service.port.organization.OrganizationModels.UserSummaryResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DDD 隔离域 RBAC1 应用服务：委托 {@link DomainAuthorizationAggregate} 与 {@link GrantDuration} 守护授权域不变量。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class DddDomainRbacService implements DomainRbacUseCase {
    private static final int MEMBER_ATTEMPTS = 3;

    private final RbacRepositoryPort repository;
    private final PermissionContextRepositoryPort contexts;
    private final GovernanceRepositoryPort governance;
    private final CacheGateway cache;
    private final AuditUseCase audit;
    private final DomainEventPublisher events;
    private final TransactionRunner transactions;

    public DddDomainRbacService(RbacRepositoryPort repository, PermissionContextRepositoryPort contexts,
                                GovernanceRepositoryPort governance, CacheGateway cache, AuditUseCase audit,
                                DomainEventPublisher events, TransactionRunner transactions) {
        this.repository = repository;
        this.contexts = contexts;
        this.governance = governance;
        this.cache = cache;
        this.audit = audit;
        this.events = events;
        this.transactions = transactions;
    }

    @Override
    public void domain(long dept, long app) {
        DddBoundary.run(() -> {
            if (!repository.departmentExists(dept)) throw DomainException.missing("部门不存在");
            if (!repository.applicationExists(app)) throw DomainException.missing("软件不存在");
        });
    }

    private void requireRoleInDomain(long id, long dept, long app) {
        if (!repository.roleExistsInDomain(id, dept, app)) {
            throw DomainException.forbidden("关联记录不属于当前隔离域");
        }
    }

    private void requirePermissionInDomain(long id, long dept, long app) {
        if (!repository.permissionExistsInDomain(id, dept, app)) {
            throw DomainException.forbidden("关联记录不属于当前隔离域");
        }
    }

    @Override
    public List<RoleResult> roles(long dept, long app) {
        domain(dept, app);
        var rows = repository.roles(dept, app);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Long>> permsByRole = new HashMap<>();
        for (RbacRepositoryPort.RolePermissionPair pair : repository.domainRolePermissionPairs(dept, app)) {
            permsByRole.computeIfAbsent(pair.roleId(), k -> new ArrayList<>()).add(pair.permissionId());
        }
        return rows.stream()
                .map(r -> new RoleResult(r.id(), r.name(), r.code(), r.description(), r.enabled(), r.createdAt(),
                        r.memberCount(), permsByRole.getOrDefault(r.id(), List.of()), r.parentRoleIds()))
                .toList();
    }

    @Override
    public void roleParents(long role, long dept, long app, List<Long> parents, String actor) {
        DddBoundary.run(() -> {
            Set<Long> wanted = new LinkedHashSet<>(parents);
            // 在写事务外先收集同域角色 ID 并与本次涉及角色合并，确保写事务首条 SQL 即为 PRIMARY(id) 升序加锁
            var lockIds = new TreeSet<>(repository.domainRoleIds(dept, app));
            lockIds.add(role);
            lockIds.addAll(wanted);
            transactions.inTransaction(() -> {
                Set<Long> domainRoleIds = repository.lockDomainRolesByIds(dept, app, lockIds);
                if (!domainRoleIds.contains(role)) {
                    throw DomainException.forbidden("关联记录不属于当前隔离域");
                }
                if (wanted.contains(role) || !domainRoleIds.containsAll(wanted)) {
                    throw DomainException.forbidden("继承角色必须属于当前隔离域且不能包含自身");
                }
                List<RbacRepositoryPort.RoleInheritanceEdge> edges = repository.domainRoleInheritanceEdges(dept, app);
                if (!wanted.isEmpty() && reachesRole(wanted, role, edges)) {
                    throw DomainException.conflict("角色继承关系不能形成循环");
                }
                List<Long> existingParents = edges.stream()
                        .filter(e -> e.childRoleId() == role)
                        .map(RbacRepositoryPort.RoleInheritanceEdge::parentRoleId)
                        .toList();
                repository.replaceRoleParents(role, dept, app, existingParents, wanted);
                cache.touchDomain(dept, app);
                events.emit(app, "role.inheritance.changed", Map.of("roleId", role, "departmentId", dept));
                audit.audit(actor, "ROLE_INHERITANCE_UPDATED", roleCode(role), dept, app, "继承角色 " + wanted.size() + " 个");
                return null;
            });
        });
    }

    private static boolean reachesRole(Set<Long> roots, long targetRoleId,
                                       List<RbacRepositoryPort.RoleInheritanceEdge> edges) {
        var parentsByChild = new HashMap<Long, List<Long>>();
        for (var edge : edges) {
            parentsByChild.computeIfAbsent(edge.childRoleId(), k -> new ArrayList<>()).add(edge.parentRoleId());
        }
        var visited = new HashSet<Long>();
        var pending = new java.util.ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            long current = pending.removeFirst();
            if (current == targetRoleId) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            pending.addAll(parentsByChild.getOrDefault(current, List.of()));
        }
        return false;
    }

    @Override
    @Transactional
    public long role(SaveRoleCommand body, Long id, long dept, long app, String actor) {
        return DddBoundary.execute(() -> {
            domain(dept, app);
            String description = text(body.description());
            if (id == null) {
                long roleId = repository.createRole(dept, app, body.name(), body.code(), description);
                audit.audit(actor, "ROLE_CREATED", body.name() + " (" + body.code() + ")", dept, app);
                return roleId;
            }
            requireRoleInDomain(id, dept, app);
            repository.updateRole(id, body.name(), body.code(), description);
            cache.touchDomain(dept, app);
            audit.audit(actor, "ROLE_UPDATED", body.name() + " (" + body.code() + ")", dept, app);
            return id;
        });
    }

    @Override
    @Transactional
    public void roleStatus(long id, long dept, long app, boolean enabled, String actor) {
        DddBoundary.run(() -> {
            requireRoleInDomain(id, dept, app);
            repository.updateRoleStatus(id, enabled);
            cache.touchDomain(dept, app);
            events.emit(app, enabled ? "role.enabled" : "role.disabled", Map.of("roleId", id, "departmentId", dept));
            audit.audit(actor, enabled ? "ROLE_ENABLED" : "ROLE_DISABLED", roleCode(id), dept, app);
        });
    }

    @Override
    @Transactional
    public void deleteRole(long id, long dept, long app, String actor) {
        DddBoundary.run(() -> {
            requireRoleInDomain(id, dept, app);
            String code = roleCode(id);
            int cancelled = governance.cancelPendingForRole(id, "角色已删除");
            repository.deleteRole(id);
            cache.touchDomain(dept, app);
            events.emit(app, "role.deleted", Map.of("roleId", id, "departmentId", dept));
            audit.audit(actor, "ROLE_DELETED", code, dept, app,
                    cancelled == 0 ? "" : "撤回待审批申请 " + cancelled + " 个");
        });
    }

    @Override
    public List<PermissionResult> permissions(long dept, long app) {
        domain(dept, app);
        return repository.permissions(dept, app).stream()
                .map(p -> new PermissionResult(p.id(), p.name(), p.code(), p.resource(), p.action(), p.createdAt(), p.roleCount()))
                .toList();
    }

    @Override
    @Transactional
    public long permission(SavePermissionCommand body, Long id, long dept, long app, String actor) {
        return DddBoundary.execute(() -> {
            domain(dept, app);
            if (id == null) {
                long permId = repository.createPermission(dept, app, body.name(), body.code(), body.resource(), body.action());
                audit.audit(actor, "PERMISSION_CREATED", body.name() + " (" + body.code() + ")", dept, app);
                return permId;
            }
            requirePermissionInDomain(id, dept, app);
            repository.updatePermission(id, body.name(), body.code(), body.resource(), body.action());
            cache.touchDomain(dept, app);
            events.emit(app, "permission.updated", Map.of("permissionId", id, "departmentId", dept));
            audit.audit(actor, "PERMISSION_UPDATED", body.name() + " (" + body.code() + ")", dept, app);
            return id;
        });
    }

    @Override
    @Transactional
    public void deletePermission(long id, long dept, long app, String actor) {
        DddBoundary.run(() -> {
            requirePermissionInDomain(id, dept, app);
            String code = permissionCode(id);
            repository.deletePermission(id);
            cache.touchDomain(dept, app);
            events.emit(app, "permission.deleted", Map.of("permissionId", id, "departmentId", dept));
            audit.audit(actor, "PERMISSION_DELETED", code, dept, app);
        });
    }

    @Override
    @Transactional
    public void rolePermissions(long role, long dept, long app, List<Long> permissions, String actor) {
        DddBoundary.run(() -> {
            requireRoleInDomain(role, dept, app);
            Set<Long> wanted = new LinkedHashSet<>(permissions);
            if (repository.countPermissionsInDomain(wanted, dept, app) != wanted.size()) {
                throw DomainException.forbidden("关联记录不属于当前隔离域");
            }
            if (!repository.lockRole(role)) {
                throw DomainException.missing("角色不存在");
            }
            Set<Long> before = new HashSet<>(repository.lockedRolePermissionIds(role));
            repository.replaceRolePermissions(role, dept, app, wanted);
            cache.touchDomain(dept, app);
            events.emit(app, "role.permissions.changed", Map.of("roleId", role, "departmentId", dept));
            audit.audit(actor, "ROLE_PERMISSIONS_UPDATED", roleCode(role), dept, app, permissionDiff(before, wanted));
        });
    }

    @Override
    public List<RoleMemberResult> roleMembers(long role, long dept, long app) {
        return DddBoundary.execute(() -> {
            requireRoleInDomain(role, dept, app);
            return repository.roleMembers(role).stream()
                    .map(m -> new RoleMemberResult(m.id(), m.name(), m.externalId(), m.email(), m.enabled()))
                    .toList();
        });
    }

    private static final class LateMembersSignal extends RuntimeException {
        final Set<Long> users;

        LateMembersSignal(Set<Long> users) {
            super(null, null, false, false);
            this.users = users;
        }
    }

    @Override
    public void roleMembers(long role, long dept, long app, List<Long> users, String actor) {
        DddBoundary.run(() -> {
            Set<Long> knownLate = new TreeSet<>();
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
        requireRoleInDomain(role, dept, app);
        DomainAuthorizationAggregate aggregate = new DomainAuthorizationAggregate(new IsolationDomain(dept, app));
        Set<Long> wanted = new LinkedHashSet<>(users);
        Set<Long> affected = new TreeSet<>(wanted);
        affected.addAll(knownLate);
        affected.addAll(repository.roleMemberIds(role));
        if (!repository.lockUsers(affected).containsAll(wanted)) {
            throw DomainException.missing("用户不存在");
        }
        if (!repository.lockRole(role)) {
            throw DomainException.missing("角色不存在");
        }
        Set<Long> before = new LinkedHashSet<>(repository.lockedRoleMemberIds(role));
        DomainAuthorizationAggregate.MemberSyncPlan plan = aggregate.planRoleMemberReplacement(wanted, affected, before);
        if (plan.requiresRetry()) {
            throw new LateMembersSignal(plan.lateMembers());
        }
        if (repository.lockedMembersCount(wanted, dept) != wanted.size()) {
            throw DomainException.forbidden("用户不属于当前部门");
        }
        repository.removeRoleMembers(role, plan.removed());
        repository.addRoleMembers(role, dept, app, plan.added(), actor);
        List<Long> changed = new ArrayList<>(plan.added());
        changed.addAll(plan.removed());
        contexts.revokeDomainForUsers(changed, dept, app);
        cache.touchDomain(dept, app);
        events.emit(app, "user.roles.changed", Map.of("roleId", role, "departmentId", dept,
                "added", plan.added().size(), "removed", plan.removed().size()));
        String roleCode = roleCode(role);
        for (long user : plan.added()) {
            audit.audit(actor, "ROLE_MEMBERS_UPDATED", "user:" + user, dept, app, "新增角色 " + roleCode);
        }
        for (long user : plan.removed()) {
            audit.audit(actor, "ROLE_MEMBERS_UPDATED", "user:" + user, dept, app, "移除角色 " + roleCode);
        }
    }

    @Override
    public UserRoleAssignmentsResult assignments(long user, long dept, long app) {
        domain(dept, app);
        Map<Long, Instant> current = repository.assignmentExpiries(user, dept, app);
        Map<Long, Instant> timed = new LinkedHashMap<>();
        current.forEach((role, at) -> {
            if (at != null) {
                timed.put(role, at);
            }
        });
        return new UserRoleAssignmentsResult(List.copyOf(current.keySet()), timed);
    }

    @Override
    public PageResult<DomainAssignmentResult> domainAssignments(long dept, long app, String keyword, int page, int size) {
        domain(dept, app);
        PageSlice<RbacRepositoryPort.RoleMemberRecord> result = repository.departmentMembers(dept, keyword, page, size);
        List<Long> userIds = result.items().stream().map(RbacRepositoryPort.RoleMemberRecord::id).toList();
        Map<Long, List<Long>> roleIdsByUser = new HashMap<>();
        Map<Long, Map<Long, Instant>> expiriesByUser = new HashMap<>();
        for (RbacRepositoryPort.DomainRolePair pair : repository.domainRolePairs(dept, app, userIds)) {
            roleIdsByUser.computeIfAbsent(pair.userId(), k -> new ArrayList<>()).add(pair.roleId());
            if (pair.expiresAt() != null) {
                expiriesByUser.computeIfAbsent(pair.userId(), k -> new LinkedHashMap<>())
                        .put(pair.roleId(), pair.expiresAt());
            }
        }
        List<DomainAssignmentResult> items = result.items().stream()
                .map(m -> new DomainAssignmentResult(m.id(), m.name(), m.externalId(), m.email(), m.enabled(),
                        roleIdsByUser.getOrDefault(m.id(), List.of()),
                        expiriesByUser.getOrDefault(m.id(), Map.of())))
                .toList();
        return PageResult.of(items, result.total(), page, size);
    }

    @Override
    @Transactional
    public BatchUserRolesResult addUserRoles(long dept, long app, BatchUserRolesCommand body, String actor) {
        return DddBoundary.execute(() -> {
            domain(dept, app);
            Set<Long> users = new TreeSet<>(body.userIds());
            Set<Long> roles = new TreeSet<>(body.roleIds());
            if (repository.lockUsers(users).size() != users.size()) {
                throw DomainException.missing("用户不存在");
            }
            if (repository.lockedMembersCount(users, dept) != users.size()) {
                throw DomainException.forbidden("用户不属于当前部门");
            }
            for (long role : roles) {
                if (!repository.lockRole(role)) {
                    throw DomainException.missing("角色不存在");
                }
                requireRoleInDomain(role, dept, app);
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Map<Long, Map<Long, Instant>> currentByUser = new LinkedHashMap<>();
            for (long user : users) {
                currentByUser.put(user, repository.lockedAssignmentExpiries(user, dept, app));
            }
            DomainAuthorizationAggregate aggregate = new DomainAuthorizationAggregate(new IsolationDomain(dept, app));
            DomainAuthorizationAggregate.BatchAdditionPlan plan =
                    aggregate.planBatchRoleAdditions(users, roles, currentByUser, body.expiresInDays(), now);
            for (Map.Entry<Long, List<Long>> entry : plan.rolesToAddByUser().entrySet()) {
                long user = entry.getKey();
                for (long role : entry.getValue()) {
                    repository.grantRole(user, dept, app, role, plan.sharedExpiry(), actor);
                }
                String external = repository.user(user).orElseThrow().externalId();
                contexts.revokeDomain(user, dept, app);
                cache.touchUser(user);
                events.emit(app, "user.roles.changed", Map.of("userId", user, "externalId", external, "departmentId", dept));
                audit.audit(actor, "USER_ROLES_UPDATED", external, dept, app,
                        "批量追加 " + String.join(", ", repository.roleCodes(entry.getValue())));
            }
            return new BatchUserRolesResult(users.size(), plan.changedUsers(), plan.grantsAdded());
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
                          List<RoleExpiryCommand> expiries, String actor) {
        DddBoundary.run(() -> {
            domain(dept, app);
            String external = repository.lockUser(user).orElseThrow(() -> DomainException.missing("用户不存在"));
            if (!repository.isLockedMember(user, dept)) {
                throw DomainException.forbidden("用户不属于当前部门");
            }
            Set<Long> wanted = new LinkedHashSet<>(roles);
            if (repository.countRolesInDomain(wanted, dept, app) != wanted.size()) {
                throw DomainException.forbidden("关联记录不属于当前隔离域");
            }
            List<GrantDuration.RoleExpiryOverride> overrides = expiries == null
                    ? List.of()
                    : expiries.stream()
                            .map(e -> new GrantDuration.RoleExpiryOverride(e.roleId(),
                                    e.expiresAt() == null ? null : e.expiresAt().toInstant()))
                            .toList();
            Map<Long, Instant> current = repository.lockedAssignmentExpiries(user, dept, app);
            DomainAuthorizationAggregate aggregate = new DomainAuthorizationAggregate(new IsolationDomain(dept, app));
            DomainAuthorizationAggregate.RoleReplacementPlan plan =
                    aggregate.planUserRoleReplacement(wanted, current, expiresInDays, overrides, Instant.now());

            repository.removeUserRoles(user, plan.removedRoleIds());
            for (DomainAuthorizationAggregate.GrantChange change : plan.grantsToApply()) {
                repository.grantRole(user, dept, app, change.roleId(), change.expiresAt(), actor);
            }
            contexts.revokeDomain(user, dept, app);
            cache.touchUser(user);
            events.emit(app, "user.roles.changed", Map.of("userId", user, "externalId", external, "departmentId", dept));
            String detail = roleDiff(current.keySet(), plan.finalRoleIds());
            if (plan.expiryChanges() > 0) {
                detail += "；调整到期时间 " + plan.expiryChanges() + " 个";
            }
            if (expiresInDays != null) {
                detail += "，" + expiresInDays + " 天后到期";
            }
            audit.audit(actor, "USER_ROLES_UPDATED", external, dept, app, detail);
        });
    }

    @Override
    @Transactional
    public DomainCopyResult copyDomain(DomainCopyCommand body, String actor) {
        return DddBoundary.execute(() -> {
            long app = body.applicationId();
            long source = body.sourceDepartmentId();
            long target = body.targetDepartmentId();
            if (source == target) {
                throw DomainException.bad("源部门与目标部门不能相同");
            }
            domain(source, app);
            domain(target, app);
            List<DomainAuthorizationAggregate.PermissionSpec> sourcePerms = toPermissionSpecs(repository.permissions(source, app));
            List<DomainAuthorizationAggregate.PermissionSpec> targetPerms = toPermissionSpecs(repository.permissions(target, app));
            List<DomainAuthorizationAggregate.RoleSpec> sourceRoles = toRoleSpecs(repository.roles(source, app));
            List<DomainAuthorizationAggregate.RoleSpec> targetRoles = toRoleSpecs(repository.roles(target, app));
            Map<Long, List<Long>> grants = new HashMap<>();
            for (RbacRepositoryPort.RolePermissionPair pair : repository.domainRolePermissionPairs(source, app)) {
                grants.computeIfAbsent(pair.roleId(), k -> new ArrayList<>()).add(pair.permissionId());
            }
            DomainAuthorizationAggregate aggregate = new DomainAuthorizationAggregate(new IsolationDomain(source, app));
            DomainAuthorizationAggregate.DomainCopyPlan plan =
                    aggregate.planCopyTo(target, sourcePerms, targetPerms, sourceRoles, targetRoles, grants);

            Map<Long, Long> permissionMap = new HashMap<>(plan.existingPermissionMap());
            for (DomainAuthorizationAggregate.PermissionSpec p : plan.permissionsToCreate()) {
                long created = repository.createPermission(target, app, p.name(), p.code(), p.resource(), p.action());
                permissionMap.put(p.id(), created);
            }
            for (DomainAuthorizationAggregate.RoleCopyItem roleCopy : plan.rolesToCreate()) {
                DomainAuthorizationAggregate.RoleSpec r = roleCopy.sourceRole();
                long createdRole = repository.createRole(target, app, r.name(), r.code(), text(r.description()), r.enabled());
                Set<Long> mappedPermissions = new LinkedHashSet<>();
                for (long oldPermId : roleCopy.sourcePermissionIds()) {
                    Long mapped = permissionMap.get(oldPermId);
                    if (mapped != null) {
                        mappedPermissions.add(mapped);
                    }
                }
                repository.replaceRolePermissions(createdRole, target, app, mappedPermissions);
            }
            int permissionsCreated = plan.permissionsToCreate().size();
            int rolesCreated = plan.rolesToCreate().size();
            audit.audit(actor, "DOMAIN_COPIED", "department:" + source + " -> department:" + target, target, app,
                    "新建权限 " + permissionsCreated + " 个、角色 " + rolesCreated + " 个；同编码跳过权限 "
                            + plan.permissionsSkipped() + " 个、角色 " + plan.rolesSkipped() + " 个");
            return new DomainCopyResult(permissionsCreated, plan.permissionsSkipped(), rolesCreated, plan.rolesSkipped());
        });
    }

    @Override
    public SimulationResult simulate(SimulationCommand body) {
        return DddBoundary.execute(() -> {
            long dept = body.departmentId();
            long app = body.applicationId();
            domain(dept, app);
            RbacRepositoryPort.UserRecord u = repository.user(body.userId()).orElseThrow(() -> DomainException.missing("用户不存在"));
            boolean appEnabled = repository.applicationEnabled(app);
            boolean member = repository.isMember(body.userId(), dept);
            List<DomainAuthorizationAggregate.AssignedDomainRole> assigned = repository.assignedRolesInDomain(body.userId(), dept, app);
            DomainAuthorizationAggregate aggregate = new DomainAuthorizationAggregate(new IsolationDomain(dept, app));
            DomainAuthorizationAggregate.DomainRoleAssessment assessment =
                    aggregate.assessSimulationRoles(u.enabled(), appEnabled, member, assigned, body.activeRoleIds(), Instant.now());
            List<DomainAuthorizationAggregate.PermissionGrant> grants = repository.permissionGrants(dept, app, assessment.activeRoleIds());
            DomainAuthorizationAggregate.SimulationOutcome outcome = aggregate.buildSimulationOutcome(
                    u.activeDepartmentId(), appEnabled, member, assessment, grants, body.permissions());
            UserSummaryResult userSummary = new UserSummaryResult(u.id(), u.name(), u.externalId(), u.email(), u.enabled(),
                    u.activeDepartmentId(), u.contextVersion(), u.createdAt());
            List<SimulatedRoleResult> roles = outcome.roles().stream()
                    .map(r -> new SimulatedRoleResult(r.id(), r.name(), r.code(), r.enabled(), r.expiresAt(), r.expired(), r.active())).toList();
            List<SimulatedPermissionResult> permissions = outcome.permissions().stream()
                    .map(p -> new SimulatedPermissionResult(p.id(), p.code(), p.name(), p.resource(), p.action(), p.grantedBy())).toList();
            return new SimulationResult(outcome.eligible(), outcome.issues(), outcome.notices(), userSummary,
                    outcome.member(), outcome.applicationEnabled(), outcome.switchesDepartment(),
                    roles, permissions, outcome.decisions(), outcome.allowed());
        });
    }

    @Override
    public DomainOverviewResult overview(long dept, long app) {
        domain(dept, app);
        RbacRepositoryPort.DomainOverviewRecord o = repository.domainOverview(dept, app);
        return new DomainOverviewResult(o.applications(), o.enabledApplications(), o.departments(), o.users(),
                o.enabledUsers(), o.departmentMembers(), o.roles(), o.permissions(), o.members(),
                o.activeKeys(), o.activeContexts(), o.auditLast24h());
    }

    private String roleCode(long id) {
        List<String> found = repository.roleCodes(List.of(id));
        return found.isEmpty() ? "#" + id : found.get(0);
    }

    private String permissionCode(long id) {
        List<String> found = repository.permissionCodes(List.of(id));
        return found.isEmpty() ? "#" + id : found.get(0);
    }

    private String roleDiff(Set<Long> before, Set<Long> after) {
        List<Long> addedIds = after.stream().filter(id -> !before.contains(id)).toList();
        List<Long> removedIds = before.stream().filter(id -> !after.contains(id)).toList();
        return DomainAuthorizationAggregate.describeSetDiff(
                repository.roleCodes(addedIds), repository.roleCodes(removedIds));
    }

    private String permissionDiff(Set<Long> before, Set<Long> after) {
        List<Long> addedIds = after.stream().filter(id -> !before.contains(id)).toList();
        List<Long> removedIds = before.stream().filter(id -> !after.contains(id)).toList();
        return DomainAuthorizationAggregate.describeSetDiff(
                repository.permissionCodes(addedIds), repository.permissionCodes(removedIds));
    }

    private static List<DomainAuthorizationAggregate.PermissionSpec> toPermissionSpecs(List<RbacRepositoryPort.PermissionRecord> rows) {
        return rows.stream().map(p -> new DomainAuthorizationAggregate.PermissionSpec(
                p.id(), p.name(), p.code(), p.resource(), p.action())).toList();
    }

    private static List<DomainAuthorizationAggregate.RoleSpec> toRoleSpecs(List<RbacRepositoryPort.RoleRecord> rows) {
        return rows.stream().map(r -> new DomainAuthorizationAggregate.RoleSpec(
                r.id(), r.name(), r.code(), r.description(), r.enabled())).toList();
    }

    private static String text(String value) {
        return Objects.requireNonNullElse(value, "").strip();
    }
}
