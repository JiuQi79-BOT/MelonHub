package com.melonhub.rbac.service.structured.organization;

import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.persistence.entity.Department;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessRequestRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminAccountRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ContextRoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.IdentityCredentialRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.IdentitySessionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.MembershipRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionContextRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleInheritanceRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RolePermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepositoryCustom;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRoleRepository;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.error.ApiException;
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
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structured 组织架构服务：管理部门与用户生命周期、跨部门成员清理与强制下线。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "structured", matchIfMissing = true)
public class StructuredOrganizationService implements OrganizationUseCase {
    private record DomainKey(long departmentId, long applicationId) {}

    private final DepartmentRepository departments;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final RolePermissionRepository rolePermissions;
    private final RoleInheritanceRepository roleInheritances;
    private final UserRoleRepository userRoles;
    private final PermissionContextRepository contexts;
    private final ContextRoleRepository contextRoles;
    private final AccessRequestRepository accessRequests;
    private final AdminAccountRepository adminAccounts;
    private final IdentityCredentialRepository identityCredentials;
    private final IdentitySessionRepository identitySessions;
    private final PermissionCache cache;
    private final AuditUseCase audit;
    private final WebhookUseCase webhooks;

    public StructuredOrganizationService(DepartmentRepository departments, UserRepository users,
                                         MembershipRepository memberships, RoleRepository roles,
                                         PermissionRepository permissions, RolePermissionRepository rolePermissions,
                                         RoleInheritanceRepository roleInheritances, UserRoleRepository userRoles,
                                         PermissionContextRepository contexts, ContextRoleRepository contextRoles,
                                         AccessRequestRepository accessRequests, AdminAccountRepository adminAccounts,
                                         IdentityCredentialRepository identityCredentials, IdentitySessionRepository identitySessions,
                                         PermissionCache cache, AuditUseCase audit, WebhookUseCase webhooks) {
        this.departments = departments;
        this.users = users;
        this.memberships = memberships;
        this.roles = roles;
        this.permissions = permissions;
        this.rolePermissions = rolePermissions;
        this.roleInheritances = roleInheritances;
        this.userRoles = userRoles;
        this.contexts = contexts;
        this.contextRoles = contextRoles;
        this.accessRequests = accessRequests;
        this.adminAccounts = adminAccounts;
        this.identityCredentials = identityCredentials;
        this.identitySessions = identitySessions;
        this.cache = cache;
        this.audit = audit;
        this.webhooks = webhooks;
    }

    @Override
    public List<DepartmentResult> departments(Collection<Long> visible) {
        List<DepartmentRepository.SummaryRow> rows = departments.findVisibleSummaries(visible);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(DepartmentRepository.SummaryRow::id).toList();
        Map<Long, Long> memberCounts = new HashMap<>();
        for (MembershipRepository.DepartmentCount c : memberships.countByDepartmentIds(ids)) {
            memberCounts.put(c.getDepartmentId(), c.getCount());
        }
        Map<Long, Long> roleCounts = new HashMap<>();
        for (RoleRepository.GroupCount c : roles.countByDepartmentIds(ids)) {
            roleCounts.put(c.getGroupId(), c.getCount());
        }
        return rows.stream()
                .map(d -> new DepartmentResult(d.id(), d.name(), d.code(), d.createdAt(),
                        memberCounts.getOrDefault(d.id(), 0L), roleCounts.getOrDefault(d.id(), 0L)))
                .toList();
    }

    @Override
    @Transactional
    public long department(SaveDepartmentCommand body, Long id, String actor) {
        if (id == null) {
            long createdId = departments.saveAndFlush(new Department().setName(body.name()).setCode(body.code())).getId();
            audit.audit(actor, "DEPARTMENT_CREATED", body.name(), createdId, null, "编码 " + body.code());
            return createdId;
        }
        if (departments.updateProfile(id, body.name(), body.code()) == 0) {
            throw ApiException.missing("部门不存在");
        }
        audit.audit(actor, "DEPARTMENT_UPDATED", body.name(), id, null, "编码 " + body.code());
        return id;
    }

    @Override
    @Transactional
    public void deleteDepartment(long id, String actor) {
        DepartmentRepository.Label label = departments.findLabelById(id)
                .orElseThrow(() -> ApiException.missing("部门不存在"));
        List<String> parts = new ArrayList<>();
        long memberCount = memberships.countByDepartmentId(id);
        long roleCount = roles.countByDepartmentId(id);
        long permCount = permissions.countByDepartmentId(id);
        long adminCount = adminAccounts.countLimitedToDepartment(id);
        if (memberCount > 0) parts.add(memberCount + " 名成员");
        if (roleCount > 0) parts.add(roleCount + " 个角色");
        if (permCount > 0) parts.add(permCount + " 个权限");
        if (adminCount > 0) parts.add(adminCount + " 名限定于该部门的管理员");
        if (!parts.isEmpty()) {
            throw ApiException.conflict("部门下还有 " + String.join("、", parts) + "，请先移除后再删除");
        }
        contexts.clearByDepartmentId(id);
        departments.deleteByIdReturningCount(id);
        audit.audit(actor, "DEPARTMENT_DELETED", label.getName(), null, null, "编码 " + label.getCode());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<UserResult> users(String keyword, Long department, Boolean enabled,
                                        Collection<Long> visibleDepartments, int page, int size) {
        Page<UserRepositoryCustom.UserRow> result = users.findPage(keyword, department, enabled, visibleDepartments, page, size);
        List<Long> userIds = result.getContent().stream().map(UserRepositoryCustom.UserRow::id).toList();
        Map<Long, List<Long>> deptsByUser = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (MembershipRepository.MembershipPair pair : memberships.findPairsByUserIdIn(userIds)) {
                deptsByUser.computeIfAbsent(pair.getUserId(), k -> new ArrayList<>()).add(pair.getDepartmentId());
            }
        }
        List<UserResult> items = result.getContent().stream()
                .map(u -> new UserResult(u.id(), u.name(), u.externalId(), u.email(), u.enabled(),
                        u.activeDepartmentId(), u.contextVersion(), u.createdAt(),
                        deptsByUser.getOrDefault(u.id(), List.of())))
                .toList();
        return PageResult.of(items, result.getTotalElements(), page, size);
    }

    @Override
    public List<DepartmentUserResult> departmentUsers(long dept) {
        if (!departments.existsById(dept)) {
            throw ApiException.missing("部门不存在");
        }
        return memberships.findDepartmentUsers(dept).stream()
                .map(u -> new DepartmentUserResult(u.getId(), u.getName(), u.getExternalId(), u.isEnabled()))
                .toList();
    }

    @Override
    @Transactional
    public long user(SaveUserCommand body, Long id, String actor) {
        return user(body, id, actor, null);
    }

    @Override
    @Transactional
    public long user(SaveUserCommand body, Long id, String actor, Collection<Long> scope) {
        Set<Long> depts = new LinkedHashSet<>(body.departmentIds());
        if (scope != null && id == null && (depts.isEmpty() || !scope.containsAll(depts))) {
            throw ApiException.forbidden("只能在自己管理的部门中创建用户");
        }
        for (long dept : depts) {
            if (!departments.existsById(dept)) {
                throw ApiException.missing("部门不存在");
            }
        }
        String email = text(body.email());
        String label = body.name() + " (" + body.externalId() + ")";
        if (id == null) {
            long createdId = users.saveAndFlush(new User().setName(body.name()).setExternalId(body.externalId())
                    .setEmail(email).setEnabled(true)).getId();
            for (long dept : depts) {
                memberships.addIfAbsent(createdId, dept);
            }
            audit.audit(actor, "USER_CREATED", label, null, null, "所属部门 " + depts.size() + " 个");
            return createdId;
        }
        String previous = users.lockExternalIdById(id).orElseThrow(() -> ApiException.missing("用户不存在"));
        if (scope != null) {
            List<Long> current = memberships.findLockedDepartmentIds(id);
            if (current.stream().noneMatch(scope::contains)) {
                throw ApiException.forbidden("该用户不在你管理的部门中");
            }
            Set<Long> outside = current.stream().filter(d -> !scope.contains(d)).collect(Collectors.toSet());
            Set<Long> wantedOutside = depts.stream().filter(d -> !scope.contains(d)).collect(Collectors.toSet());
            if (!outside.equals(wantedOutside)) {
                throw ApiException.forbidden("不能调整自己管理范围以外的部门成员关系");
            }
            UserRepository.UserSummary existing = users.findSummaryById(id).orElseThrow();
            boolean profileChanged = !previous.equals(body.externalId())
                    || !body.name().equals(existing.getName())
                    || !text(body.email()).equals(existing.getEmail());
            if (!outside.isEmpty() && profileChanged) {
                throw ApiException.forbidden("该用户还属于你管理范围以外的部门，只能调整所管部门内的成员关系");
            }
        }
        boolean identityChanged = !previous.equals(body.externalId());
        users.updateUser(id, body.name(), body.externalId(), email, identityChanged ? 1L : 0L);
        if (identityChanged) {
            identityCredentials.renameUsername(id, body.externalId());
            identitySessions.revokeUserSessions(id);
        }
        List<Long> removed = memberships.findLockedDepartmentIds(id).stream().filter(d -> !depts.contains(d)).toList();
        List<UserRoleRepository.DepartmentAssignment> removedAssignments = removed.isEmpty()
                ? List.of()
                : userRoles.findAssignmentsInDepartments(id, removed);
        if (!removed.isEmpty()) {
            userRoles.deleteInDepartments(id, removed);
        }
        for (long dept : removed) {
            memberships.deleteByUserIdAndDepartmentId(id, dept);
        }
        for (long dept : depts) {
            memberships.addIfAbsent(id, dept);
        }
        users.clearInactiveDepartment(id);
        cache.touchUser(id);
        webhooks.emitAll("user.updated", Map.of("userId", id, "externalId", body.externalId()));
        Map<DomainKey, List<UserRoleRepository.DepartmentAssignment>> groupedRemoved = removedAssignments.stream()
                .collect(Collectors.groupingBy(
                        row -> new DomainKey(row.getDepartmentId(), row.getApplicationId()),
                        LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<DomainKey, List<UserRoleRepository.DepartmentAssignment>> group : groupedRemoved.entrySet()) {
            long dept = group.getKey().departmentId();
            long app = group.getKey().applicationId();
            List<Long> roleIds = group.getValue().stream().map(UserRoleRepository.DepartmentAssignment::getRoleId).toList();
            List<String> roleCodes = group.getValue().stream().map(UserRoleRepository.DepartmentAssignment::getRoleCode).toList();
            contexts.revokeDomain(id, dept, app);
            webhooks.emit(app, "user.roles.changed", Map.of("userId", id, "externalId", body.externalId(),
                    "departmentId", dept, "removed", roleIds.size()));
            audit.audit(actor, "USER_ROLES_UPDATED", body.externalId(), dept, app, "移除角色 " + String.join(", ", roleCodes));
        }
        List<String> detail = new ArrayList<>();
        if (identityChanged) {
            detail.add("外部标识由 " + previous + " 变更，原有上下文已失效");
        }
        if (!removed.isEmpty()) {
            detail.add("移出部门 " + removed.size() + " 个，相关角色已解除");
        }
        audit.audit(actor, "USER_UPDATED", label, null, null, String.join("；", detail));
        return id;
    }

    @Override
    @Transactional
    public void userStatus(long id, boolean enabled, String actor) {
        userStatus(id, enabled, actor, null);
    }

    @Override
    @Transactional
    public void userStatus(long id, boolean enabled, String actor, Collection<Long> scope) {
        String external = lockOwnedUser(id, scope);
        users.updateStatus(id, enabled);
        if (!enabled) {
            identitySessions.revokeUserSessions(id);
        }
        cache.touchUser(id);
        webhooks.emitAll(enabled ? "user.enabled" : "user.disabled", Map.of("userId", id, "externalId", external));
        audit.audit(actor, enabled ? "USER_ENABLED" : "USER_DISABLED", external, null, null, enabled ? "" : "原有上下文已失效");
    }

    @Override
    @Transactional
    public void deleteUser(long id, String actor, Collection<Long> scope) {
        String external = users.lockExternalIdById(id).orElseThrow(() -> ApiException.missing("用户不存在"));
        requireOwned(id, scope);
        int cancelled = accessRequests.cancelPendingForUser(id, "用户已删除");
        users.deleteByIdReturningCount(id);
        cache.touchUser(id);
        webhooks.emitAll("user.deleted", Map.of("userId", id, "externalId", external));
        audit.audit(actor, "USER_DELETED", external, null, null, cancelled == 0 ? "" : "撤回待审批申请 " + cancelled + " 个");
    }

    @Override
    public String lockOwnedUser(long id, Collection<Long> scope) {
        String external = users.lockExternalIdById(id).orElseThrow(() -> ApiException.missing("用户不存在"));
        requireOwned(id, scope);
        return external;
    }

    @Override
    public UserAccessResult userAccess(long id, Visibility visible) {
        UserRepository.UserSummary u = users.findSummaryById(id).orElseThrow(() -> ApiException.missing("用户不存在"));
        List<Long> memberOf = memberships.findDepartmentIdsByUserId(id);
        if (visible.departments() != null && memberOf.stream().noneMatch(visible::department)) {
            throw ApiException.forbidden("该用户不在你管理的部门中");
        }
        UserResult userView = new UserResult(u.getId(), u.getName(), u.getExternalId(), u.getEmail(), u.isEnabled(),
                u.getActiveDepartmentId(), u.getContextVersion(), u.getCreatedAt(), memberOf);
        Map<DomainKey, UserRoleRepository.AccessRole> domainMeta = new LinkedHashMap<>();
        Map<DomainKey, List<AccessDomainRoleResult>> domainRoles = new LinkedHashMap<>();
        Map<DomainKey, List<String>> domainPerms = new LinkedHashMap<>();
        for (UserRoleRepository.AccessRole row : userRoles.findAccessRoles(id)) {
            if (!visible.application(row.getApplicationId()) || !visible.department(row.getDepartmentId())) {
                continue;
            }
            DomainKey key = new DomainKey(row.getDepartmentId(), row.getApplicationId());
            domainMeta.putIfAbsent(key, row);
            domainRoles.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new AccessDomainRoleResult(row.getId(), row.getName(), row.getCode(), row.isEnabled(), row.getExpiresAt(), row.getGrantedBy()));
            domainPerms.computeIfAbsent(key, k -> new ArrayList<>());
        }
        Map<DomainKey, List<Long>> groupedDirect = new LinkedHashMap<>();
        for (UserRoleRepository.DirectRoleDomain row : userRoles.findActiveDirectRoles(id)) {
            groupedDirect.computeIfAbsent(new DomainKey(row.getDepartmentId(), row.getApplicationId()), k -> new ArrayList<>())
                    .add(row.getRoleId());
        }
        for (Map.Entry<DomainKey, List<Long>> entry : groupedDirect.entrySet()) {
            List<String> perms = domainPerms.get(entry.getKey());
            if (perms == null) {
                continue;
            }
            List<Long> expanded = roleInheritances.expandActiveRoleIds(
                    entry.getValue(), entry.getKey().departmentId(), entry.getKey().applicationId());
            if (!expanded.isEmpty()) {
                perms.addAll(rolePermissions.findActivePermissionCodesByRoleIds(expanded));
            }
        }
        List<AccessDomainResult> domains = domainMeta.entrySet().stream().map(e -> {
            UserRoleRepository.AccessRole m = e.getValue();
            return new AccessDomainResult(m.getDepartmentId(), m.getDepartmentName(), m.getApplicationId(), m.getApplicationName(),
                    m.isApplicationEnabled(), domainRoles.get(e.getKey()), domainPerms.get(e.getKey()));
        }).toList();
        List<PermissionContextRepository.ActiveContext> activeRows = contexts.findActiveByUserId(id, Limit.of(200));
        Map<String, Long> ctxRoleCounts = new HashMap<>();
        if (!activeRows.isEmpty()) {
            List<String> ctxIds = activeRows.stream().map(PermissionContextRepository.ActiveContext::getId).toList();
            for (ContextRoleRepository.ContextRoleCount c : contextRoles.countByContextIds(ctxIds)) {
                ctxRoleCounts.put(c.getContextId(), c.getCount());
            }
        }
        List<ActiveContextResult> activeContexts = activeRows.stream()
                .filter(c -> visible.application(c.getApplicationId()) && visible.department(c.getDepartmentId()))
                .map(c -> new ActiveContextResult(c.getDepartmentId(), c.getDepartmentName(), c.getApplicationId(), c.getApplicationName(),
                        c.getCreatedAt(), c.getExpiresAt(), ctxRoleCounts.getOrDefault(c.getId(), 0L), c.isValid() ? 1L : 0L))
                .toList();
        return new UserAccessResult(userView, domains, activeContexts);
    }

    @Override
    @Transactional
    public int revokeUserContexts(long id, String actor, Collection<Long> scope) {
        String external = users.lockExternalIdById(id).orElseThrow(() -> ApiException.missing("用户不存在"));
        requireOwned(id, scope);
        users.bumpContextVersion(id);
        int revoked = contexts.revokeByUserId(id);
        cache.touchUser(id);
        webhooks.emitAll("user.contexts.revoked", Map.of("userId", id, "externalId", external));
        audit.audit(actor, "USER_CONTEXTS_REVOKED", external, null, null, "强制下线 " + revoked + " 个上下文");
        return revoked;
    }

    private void requireOwned(long user, Collection<Long> scope) {
        if (scope == null) {
            return;
        }
        List<Long> userDepts = memberships.findLockedDepartmentIds(user);
        if (userDepts.isEmpty() || !scope.containsAll(userDepts)) {
            throw ApiException.forbidden("只能操作完全属于你所管部门的用户");
        }
    }

    private static String text(String value) {
        return Objects.requireNonNullElse(value, "").strip();
    }
}
