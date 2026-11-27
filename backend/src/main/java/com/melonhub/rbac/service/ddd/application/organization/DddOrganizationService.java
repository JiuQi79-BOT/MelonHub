package com.melonhub.rbac.service.ddd.application.organization;

import com.melonhub.rbac.service.ddd.application.shared.DddBoundary;
import com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate;
import com.melonhub.rbac.service.ddd.domain.model.UserMembershipAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.GovernanceRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.IdentityRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PageSlice;
import com.melonhub.rbac.service.ddd.domain.repository.PermissionContextRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CacheGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.DomainEventPublisher;
import com.melonhub.rbac.service.ddd.domain.repository.RbacRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.IsolationDomain;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.AccessDomainResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.AccessDomainRoleResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.ActiveContextResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.DepartmentResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.DepartmentUserResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveDepartmentCommand;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveUserCommand;
import com.melonhub.rbac.service.port.organization.OrganizationModels.UserAccessResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.UserResult;
import com.melonhub.rbac.service.port.organization.OrganizationUseCase;
import com.melonhub.rbac.service.port.security.Visibility;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DDD 组织架构应用服务：协调部门删除守卫与 {@link UserMembershipAggregate} 成员关系变更。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class DddOrganizationService implements OrganizationUseCase {
    private final RbacRepositoryPort repository;
    private final PermissionContextRepositoryPort contexts;
    private final GovernanceRepositoryPort governance;
    private final IdentityRepositoryPort identity;
    private final CacheGateway cache;
    private final AuditUseCase audit;
    private final DomainEventPublisher events;

    public DddOrganizationService(RbacRepositoryPort repository, PermissionContextRepositoryPort contexts,
                                  GovernanceRepositoryPort governance, IdentityRepositoryPort identity,
                                  CacheGateway cache, AuditUseCase audit, DomainEventPublisher events) {
        this.repository = repository;
        this.contexts = contexts;
        this.governance = governance;
        this.identity = identity;
        this.cache = cache;
        this.audit = audit;
        this.events = events;
    }

    @Override
    public List<DepartmentResult> departments(Collection<Long> visible) {
        return repository.departments(visible).stream()
                .map(d -> new DepartmentResult(d.id(), d.name(), d.code(), d.createdAt(), d.memberCount(), d.roleCount()))
                .toList();
    }

    @Override
    @Transactional
    public long department(SaveDepartmentCommand body, Long id, String actor) {
        return DddBoundary.execute(() -> {
            if (id == null) {
                long deptId = repository.createDepartment(body.name(), body.code());
                audit.audit(actor, "DEPARTMENT_CREATED", body.name(), deptId, null, "编码 " + body.code());
                return deptId;
            }
            if (repository.updateDepartment(id, body.name(), body.code()) == 0) {
                throw DomainException.missing("部门不存在");
            }
            audit.audit(actor, "DEPARTMENT_UPDATED", body.name(), id, null, "编码 " + body.code());
            return id;
        });
    }

    @Override
    @Transactional
    public void deleteDepartment(long id, String actor) {
        DddBoundary.run(() -> {
            RbacRepositoryPort.EntityLabel label = repository.departmentLabel(id)
                    .orElseThrow(() -> DomainException.missing("部门不存在"));
            DomainAuthorizationAggregate.assertDepartmentDeletable(repository.departmentUsage(id));
            contexts.revokeDepartment(id);
            repository.deleteDepartment(id);
            audit.audit(actor, "DEPARTMENT_DELETED", label.name(), null, null, "编码 " + label.code());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<UserResult> users(String keyword, Long department, Boolean enabled,
                                        Collection<Long> visibleDepartments, int page, int size) {
        PageSlice<RbacRepositoryPort.UserRecord> result = repository.users(keyword, department, enabled, visibleDepartments, page, size);
        List<Long> userIds = result.items().stream().map(RbacRepositoryPort.UserRecord::id).toList();
        Map<Long, List<Long>> deptsByUser = new HashMap<>();
        for (RbacRepositoryPort.MembershipPair pair : repository.memberships(userIds)) {
            deptsByUser.computeIfAbsent(pair.userId(), k -> new ArrayList<>()).add(pair.departmentId());
        }
        List<UserResult> items = result.items().stream()
                .map(u -> new UserResult(u.id(), u.name(), u.externalId(), u.email(), u.enabled(),
                        u.activeDepartmentId(), u.contextVersion(), u.createdAt(),
                        deptsByUser.getOrDefault(u.id(), List.of())))
                .toList();
        return PageResult.of(items, result.total(), page, size);
    }

    @Override
    public List<DepartmentUserResult> departmentUsers(long dept) {
        return DddBoundary.execute(() -> {
            if (!repository.departmentExists(dept)) {
                throw DomainException.missing("部门不存在");
            }
            return repository.departmentUsers(dept).stream()
                    .map(u -> new DepartmentUserResult(u.id(), u.name(), u.externalId(), u.enabled()))
                    .toList();
        });
    }

    @Override
    @Transactional
    public long user(SaveUserCommand body, Long id, String actor) {
        return user(body, id, actor, null);
    }

    @Override
    @Transactional
    public long user(SaveUserCommand body, Long id, String actor, Collection<Long> scope) {
        return DddBoundary.execute(() -> {
            Set<Long> departments = new LinkedHashSet<>(body.departmentIds());
            if (id == null) {
                UserMembershipAggregate.assertCreateDepartmentsInScope(departments, scope);
            }
            for (long dept : departments) {
                if (!repository.departmentExists(dept)) {
                    throw DomainException.missing("部门不存在");
                }
            }
            String email = text(body.email());
            String label = body.name() + " (" + body.externalId() + ")";
            if (id == null) {
                long createdId = repository.createUser(body.name(), body.externalId(), email);
                for (long dept : departments) {
                    repository.addMembership(createdId, dept);
                }
                audit.audit(actor, "USER_CREATED", label, null, null, "所属部门 " + departments.size() + " 个");
                return createdId;
            }
            String previousExternal = repository.lockUser(id).orElseThrow(() -> DomainException.missing("用户不存在"));
            List<Long> currentDepartments = repository.lockedUserDepartments(id);
            RbacRepositoryPort.UserRecord existingRow = repository.user(id).orElseThrow(() -> DomainException.missing("用户不存在"));
            UserMembershipAggregate aggregate = new UserMembershipAggregate(
                    id, existingRow.name(), previousExternal, existingRow.email(), currentDepartments);
            List<Long> removedPreview = currentDepartments.stream().filter(d -> !departments.contains(d)).toList();
            List<UserMembershipAggregate.DomainGrant> staleAssignments =
                    repository.userRoleAssignmentsInDepartments(id, removedPreview);
            UserMembershipAggregate.UpdatePlan plan = aggregate.planUpdate(
                    body.name(), body.externalId(), body.email(), departments, scope, staleAssignments);

            repository.updateUser(id, body.name(), body.externalId(), plan.normalizedEmail(), plan.externalIdChanged());
            if (plan.externalIdChanged()) {
                identity.renameCredential(id, body.externalId());
                identity.revokeUserSessions(id);
            }
            repository.removeUserRolesInDepartments(id, plan.removedDepartments());
            for (long dept : plan.removedDepartments()) {
                repository.removeMembership(id, dept);
            }
            for (long dept : plan.finalDepartments()) {
                repository.addMembership(id, dept);
            }
            repository.clearInactiveDepartment(id);
            cache.touchUser(id);
            events.emitAll("user.updated", Map.of("userId", id, "externalId", body.externalId()));
            for (UserMembershipAggregate.StaleDomainRevocation rev : plan.staleRevocations()) {
                long dept = rev.domain().departmentId();
                long app = rev.domain().applicationId();
                contexts.revokeDomain(id, dept, app);
                events.emit(app, "user.roles.changed", Map.of("userId", id, "externalId", body.externalId(),
                        "departmentId", dept, "removed", rev.roleIds().size()));
                audit.audit(actor, "USER_ROLES_UPDATED", body.externalId(), dept, app,
                        "移除角色 " + String.join(", ", rev.roleCodes()));
            }
            audit.audit(actor, "USER_UPDATED", label, null, null, plan.auditDetail(previousExternal));
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
            if (!enabled) {
                identity.revokeUserSessions(id);
            }
            cache.touchUser(id);
            events.emitAll(enabled ? "user.enabled" : "user.disabled", Map.of("userId", id, "externalId", external));
            audit.audit(actor, enabled ? "USER_ENABLED" : "USER_DISABLED", external, null, null,
                    enabled ? "" : "原有上下文已失效");
        });
    }

    @Override
    @Transactional
    public void deleteUser(long id, String actor, Collection<Long> scope) {
        DddBoundary.run(() -> {
            String external = lockOwnedUser(id, scope);
            int cancelled = governance.cancelPendingForUser(id, "用户已删除");
            repository.deleteUser(id);
            cache.touchUser(id);
            events.emitAll("user.deleted", Map.of("userId", id, "externalId", external));
            audit.audit(actor, "USER_DELETED", external, null, null,
                    cancelled == 0 ? "" : "撤回待审批申请 " + cancelled + " 个");
        });
    }

    @Override
    public String lockOwnedUser(long id, Collection<Long> scope) {
        return DddBoundary.execute(() -> {
            String external = repository.lockUser(id).orElseThrow(() -> DomainException.missing("用户不存在"));
            if (scope != null) {
                UserMembershipAggregate aggregate = new UserMembershipAggregate(
                        id, "", external, "", repository.lockedUserDepartments(id));
                aggregate.assertFullyOwnedBy(scope);
            }
            return external;
        });
    }

    @Override
    public UserAccessResult userAccess(long id, Visibility visible) {
        return DddBoundary.execute(() -> {
            RbacRepositoryPort.UserRecord u = repository.user(id).orElseThrow(() -> DomainException.missing("用户不存在"));
            List<Long> memberOf = repository.userDepartments(id);
            if (visible.departments() != null && memberOf.stream().noneMatch(visible::department)) {
                throw DomainException.forbidden("该用户不在你管理的部门中");
            }
            UserResult userView = new UserResult(u.id(), u.name(), u.externalId(), u.email(), u.enabled(),
                    u.activeDepartmentId(), u.contextVersion(), u.createdAt(), memberOf);
            Map<IsolationDomain, RbacRepositoryPort.AccessRoleRecord> domainMeta = new LinkedHashMap<>();
            Map<IsolationDomain, List<AccessDomainRoleResult>> domainRoles = new LinkedHashMap<>();
            Map<IsolationDomain, List<String>> domainPerms = new LinkedHashMap<>();
            for (RbacRepositoryPort.AccessRoleRecord row : repository.accessRoles(id)) {
                if (!visible.application(row.applicationId()) || !visible.department(row.departmentId())) {
                    continue;
                }
                IsolationDomain key = new IsolationDomain(row.departmentId(), row.applicationId());
                domainMeta.putIfAbsent(key, row);
                domainRoles.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new AccessDomainRoleResult(row.id(), row.name(), row.code(), row.enabled(), row.expiresAt(), row.grantedBy()));
                domainPerms.computeIfAbsent(key, k -> new ArrayList<>());
            }
            for (RbacRepositoryPort.AccessPermissionRecord row : repository.accessPermissions(id)) {
                List<String> perms = domainPerms.get(new IsolationDomain(row.departmentId(), row.applicationId()));
                if (perms != null) {
                    perms.add(row.code());
                }
            }
            List<AccessDomainResult> domains = domainMeta.entrySet().stream().map(e -> {
                RbacRepositoryPort.AccessRoleRecord m = e.getValue();
                return new AccessDomainResult(m.departmentId(), m.departmentName(), m.applicationId(), m.applicationName(),
                        m.applicationEnabled(), domainRoles.get(e.getKey()), domainPerms.get(e.getKey()));
            }).toList();
            List<ActiveContextResult> activeContexts = contexts.activeContexts(id).stream()
                    .filter(c -> visible.application(c.applicationId()) && visible.department(c.departmentId()))
                    .map(c -> new ActiveContextResult(c.departmentId(), c.departmentName(), c.applicationId(), c.applicationName(),
                            c.createdAt(), c.expiresAt(), c.activeRoleCount(), c.valid()))
                    .toList();
            return new UserAccessResult(userView, domains, activeContexts);
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
            audit.audit(actor, "USER_CONTEXTS_REVOKED", external, null, null, "强制下线 " + revoked + " 个上下文");
            return revoked;
        });
    }

    private static String text(String value) {
        return Objects.requireNonNullElse(value, "").strip();
    }
}
