package com.melonhub.rbac.service.structured.domainrbac;

import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.persistence.entity.Permission;
import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessRequestRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationKeyRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.MembershipRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionContextRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleInheritanceRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RolePermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRoleRepository;
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
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.organization.OrganizationModels.UserSummaryResult;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Structured 隔离域 RBAC1 服务：负责角色、权限、继承、角色成员维护、用户授权分配、跨部门复制与权限模拟诊断。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "structured", matchIfMissing = true)
public class StructuredDomainRbacService implements DomainRbacUseCase {
    private static final int MEMBER_ATTEMPTS = 3;

    private final DepartmentRepository departments;
    private final ApplicationRepository applications;
    private final ApplicationKeyRepository applicationKeys;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final RolePermissionRepository rolePermissions;
    private final RoleInheritanceRepository roleInheritances;
    private final UserRoleRepository userRoles;
    private final PermissionContextRepository contexts;
    private final AccessRequestRepository accessRequests;
    private final AuditEventRepository auditEvents;
    private final PermissionCache cache;
    private final AuditUseCase audit;
    private final WebhookUseCase webhooks;
    private final TransactionTemplate memberAttempt;

    public StructuredDomainRbacService(DepartmentRepository departments, ApplicationRepository applications,
                                       ApplicationKeyRepository applicationKeys, UserRepository users,
                                       MembershipRepository memberships, RoleRepository roles,
                                       PermissionRepository permissions, RolePermissionRepository rolePermissions,
                                       RoleInheritanceRepository roleInheritances, UserRoleRepository userRoles,
                                       PermissionContextRepository contexts, AccessRequestRepository accessRequests,
                                       AuditEventRepository auditEvents, PermissionCache cache,
                                       AuditUseCase audit, WebhookUseCase webhooks,
                                       PlatformTransactionManager transactionManager) {
        this.departments = departments;
        this.applications = applications;
        this.applicationKeys = applicationKeys;
        this.users = users;
        this.memberships = memberships;
        this.roles = roles;
        this.permissions = permissions;
        this.rolePermissions = rolePermissions;
        this.roleInheritances = roleInheritances;
        this.userRoles = userRoles;
        this.contexts = contexts;
        this.accessRequests = accessRequests;
        this.auditEvents = auditEvents;
        this.cache = cache;
        this.audit = audit;
        this.webhooks = webhooks;
        this.memberAttempt = new TransactionTemplate(transactionManager);
        this.memberAttempt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public void domain(long dept, long app) {
        if (!departments.existsById(dept)) {
            throw ApiException.missing("部门不存在");
        }
        if (!applications.existsById(app)) {
            throw ApiException.missing("软件不存在");
        }
    }

    private void requireRoleInDomain(long id, long dept, long app) {
        if (!roles.existsByIdAndDepartmentIdAndApplicationId(id, dept, app)) {
            throw ApiException.forbidden("关联记录不属于当前隔离域");
        }
    }

    private void requirePermissionInDomain(long id, long dept, long app) {
        if (!permissions.existsByIdAndDepartmentIdAndApplicationId(id, dept, app)) {
            throw ApiException.forbidden("关联记录不属于当前隔离域");
        }
    }

    @Override
    public List<RoleResult> roles(long dept, long app) {
        domain(dept, app);
        List<RoleRepository.RoleSummary> rows = roles.findSummariesByDomain(dept, app);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = rows.stream().map(RoleRepository.RoleSummary::getId).toList();
        Map<Long, Long> memberCounts = new HashMap<>();
        for (UserRoleRepository.RoleMemberCount c : userRoles.countByRoleIds(roleIds)) {
            memberCounts.put(c.getRoleId(), c.getCount());
        }
        Map<Long, List<Long>> parentsByChild = new HashMap<>();
        for (RoleInheritanceRepository.Edge edge : roleInheritances.findEdgesByDomain(dept, app)) {
            parentsByChild.computeIfAbsent(edge.getChildRoleId(), k -> new ArrayList<>()).add(edge.getParentRoleId());
        }
        Map<Long, List<Long>> permsByRole = new HashMap<>();
        for (RolePermissionRepository.RolePermissionPair pair : rolePermissions.findPairsByDomain(dept, app)) {
            permsByRole.computeIfAbsent(pair.getRoleId(), k -> new ArrayList<>()).add(pair.getPermissionId());
        }
        return rows.stream()
                .map(r -> new RoleResult(r.getId(), r.getName(), r.getCode(), r.getDescription(), r.isEnabled(), r.getCreatedAt(),
                        memberCounts.getOrDefault(r.getId(), 0L),
                        permsByRole.getOrDefault(r.getId(), List.of()),
                        List.copyOf(parentsByChild.getOrDefault(r.getId(), List.of()))))
                .toList();
    }

    @Override
    public void roleParents(long role, long dept, long app, List<Long> parents, String actor) {
        Set<Long> wanted = new LinkedHashSet<>(parents);
        // 在写事务外先收集同域角色 ID 并与本次涉及角色合并，确保写事务首条 SQL 即为 PRIMARY(id) 升序加锁
        var lockIds = new TreeSet<>(roles.findIdsByDomain(dept, app));
        lockIds.add(role);
        lockIds.addAll(wanted);
        memberAttempt.executeWithoutResult(status -> {
            Set<Long> domainRoleIds = roles.lockDomainRolesByIds(dept, app, lockIds);
            if (!domainRoleIds.contains(role)) {
                throw ApiException.forbidden("关联记录不属于当前隔离域");
            }
            if (wanted.contains(role) || !domainRoleIds.containsAll(wanted)) {
                throw ApiException.forbidden("继承角色必须属于当前隔离域且不能包含自身");
            }
            // 写事务内首条普通查询在持有全部同域角色排他锁后创建 ReadView，既无 S 锁又能看见前序已提交边
            List<RoleInheritanceRepository.Edge> edges = roleInheritances.findEdgesByDomain(dept, app);
            if (!wanted.isEmpty() && RoleInheritanceRepository.expandEdges(wanted, edges).contains(role)) {
                throw ApiException.conflict("角色继承关系不能形成循环");
            }
            List<Long> existingParents = edges.stream()
                    .filter(e -> e.getChildRoleId() == role)
                    .map(RoleInheritanceRepository.Edge::getParentRoleId)
                    .toList();
            roleInheritances.replaceParents(role, dept, app, existingParents, wanted);
            cache.touchDomain(dept, app);
            webhooks.emit(app, "role.inheritance.changed", Map.of("roleId", role, "departmentId", dept));
            audit.audit(actor, "ROLE_INHERITANCE_UPDATED", roleCode(role), dept, app, "继承角色 " + wanted.size() + " 个");
        });
    }

    @Override
    @Transactional
    public long role(SaveRoleCommand body, Long id, long dept, long app, String actor) {
        domain(dept, app);
        String description = text(body.description());
        if (id == null) {
            long createdId = roles.saveAndFlush(new Role()
                    .setDepartment(departments.getReferenceById(dept))
                    .setApplication(applications.getReferenceById(app))
                    .setName(body.name()).setCode(body.code()).setDescription(description).setEnabled(true)).getId();
            audit.audit(actor, "ROLE_CREATED", body.name() + " (" + body.code() + ")", dept, app);
            return createdId;
        }
        requireRoleInDomain(id, dept, app);
        roles.updateProfile(id, body.name(), body.code(), description);
        cache.touchDomain(dept, app);
        audit.audit(actor, "ROLE_UPDATED", body.name() + " (" + body.code() + ")", dept, app);
        return id;
    }

    @Override
    @Transactional
    public void roleStatus(long id, long dept, long app, boolean enabled, String actor) {
        requireRoleInDomain(id, dept, app);
        roles.updateStatus(id, enabled);
        cache.touchDomain(dept, app);
        webhooks.emit(app, enabled ? "role.enabled" : "role.disabled", Map.of("roleId", id, "departmentId", dept));
        audit.audit(actor, enabled ? "ROLE_ENABLED" : "ROLE_DISABLED", roleCode(id), dept, app);
    }

    @Override
    @Transactional
    public void deleteRole(long id, long dept, long app, String actor) {
        requireRoleInDomain(id, dept, app);
        String code = roleCode(id);
        int cancelled = accessRequests.cancelPendingForRole(id, "角色已删除");
        roles.deleteByIdReturningCount(id);
        cache.touchDomain(dept, app);
        webhooks.emit(app, "role.deleted", Map.of("roleId", id, "departmentId", dept));
        audit.audit(actor, "ROLE_DELETED", code, dept, app, cancelled == 0 ? "" : "撤回待审批申请 " + cancelled + " 个");
    }

    @Override
    public List<PermissionResult> permissions(long dept, long app) {
        domain(dept, app);
        List<PermissionRepository.PermissionSummary> rows = permissions.findSummariesByDomain(dept, app);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(PermissionRepository.PermissionSummary::getId).toList();
        Map<Long, Long> roleCounts = new HashMap<>();
        for (RolePermissionRepository.PermissionRoleCount c : rolePermissions.countRolesByPermissionIds(ids)) {
            roleCounts.put(c.getPermissionId(), c.getCount());
        }
        return rows.stream()
                .map(p -> new PermissionResult(p.getId(), p.getName(), p.getCode(), p.getResource(), p.getAction(),
                        p.getCreatedAt(), roleCounts.getOrDefault(p.getId(), 0L)))
                .toList();
    }

    @Override
    @Transactional
    public long permission(SavePermissionCommand body, Long id, long dept, long app, String actor) {
        domain(dept, app);
        if (id == null) {
            long createdId = permissions.saveAndFlush(new Permission()
                    .setDepartment(departments.getReferenceById(dept))
                    .setApplication(applications.getReferenceById(app))
                    .setName(body.name()).setCode(body.code()).setResource(body.resource()).setAction(body.action())).getId();
            audit.audit(actor, "PERMISSION_CREATED", body.name() + " (" + body.code() + ")", dept, app);
            return createdId;
        }
        requirePermissionInDomain(id, dept, app);
        permissions.updateProfile(id, body.name(), body.code(), body.resource(), body.action());
        cache.touchDomain(dept, app);
        webhooks.emit(app, "permission.updated", Map.of("permissionId", id, "departmentId", dept));
        audit.audit(actor, "PERMISSION_UPDATED", body.name() + " (" + body.code() + ")", dept, app);
        return id;
    }

    @Override
    @Transactional
    public void deletePermission(long id, long dept, long app, String actor) {
        requirePermissionInDomain(id, dept, app);
        String code = permissionCode(id);
        permissions.deleteByIdReturningCount(id);
        cache.touchDomain(dept, app);
        webhooks.emit(app, "permission.deleted", Map.of("permissionId", id, "departmentId", dept));
        audit.audit(actor, "PERMISSION_DELETED", code, dept, app);
    }

    @Override
    @Transactional
    public void rolePermissions(long role, long dept, long app, List<Long> permissionIds, String actor) {
        requireRoleInDomain(role, dept, app);
        Set<Long> wanted = new LinkedHashSet<>(permissionIds);
        if (!wanted.isEmpty() && permissions.countByDepartmentIdAndApplicationIdAndIdIn(dept, app, wanted) != wanted.size()) {
            throw ApiException.forbidden("关联记录不属于当前隔离域");
        }
        if (!roles.lockById(role)) {
            throw ApiException.missing("角色不存在");
        }
        Set<Long> before = new HashSet<>(rolePermissions.findLockedPermissionIds(role));
        rolePermissions.replaceRolePermissions(role, dept, app, wanted);
        cache.touchDomain(dept, app);
        webhooks.emit(app, "role.permissions.changed", Map.of("roleId", role, "departmentId", dept));
        audit.audit(actor, "ROLE_PERMISSIONS_UPDATED", roleCode(role), dept, app, permissionDiff(before, wanted));
    }

    @Override
    public List<RoleMemberResult> roleMembers(long role, long dept, long app) {
        requireRoleInDomain(role, dept, app);
        return userRoles.findRoleMembers(role).stream()
                .map(m -> new RoleMemberResult(m.getId(), m.getName(), m.getExternalId(), m.getEmail(), m.isEnabled()))
                .toList();
    }

    @Override
    public void roleMembers(long role, long dept, long app, List<Long> userIds, String actor) {
        Set<Long> late = new TreeSet<>();
        for (int attempt = 1; ; attempt++) {
            try {
                memberAttempt.executeWithoutResult(status -> replaceRoleMembers(role, dept, app, userIds, late, actor));
                return;
            } catch (LateMembers e) {
                if (attempt >= MEMBER_ATTEMPTS) {
                    throw ApiException.busy("SERVICE_BUSY", "角色成员正在被同时修改，请稍后重试");
                }
                late.addAll(e.users);
            }
        }
    }

    private static final class LateMembers extends RuntimeException {
        final Set<Long> users;

        LateMembers(Set<Long> users) {
            super(null, null, false, false);
            this.users = users;
        }
    }

    private void replaceRoleMembers(long role, long dept, long app, List<Long> userIds, Set<Long> known, String actor) {
        requireRoleInDomain(role, dept, app);
        Set<Long> wanted = new LinkedHashSet<>(userIds);
        Set<Long> affected = new TreeSet<>(wanted);
        affected.addAll(known);
        affected.addAll(userRoles.findRoleMemberIds(role));
        if (!users.lockIdsByIdIn(affected).containsAll(wanted)) {
            throw ApiException.missing("用户不存在");
        }
        if (!roles.lockById(role)) {
            throw ApiException.missing("角色不存在");
        }
        Set<Long> before = new LinkedHashSet<>(userRoles.findLockedRoleMemberIds(role));
        Set<Long> late = new TreeSet<>(before);
        late.removeAll(affected);
        if (!late.isEmpty()) {
            throw new LateMembers(late);
        }
        if (memberships.countLocked(wanted, dept) != wanted.size()) {
            throw ApiException.forbidden("用户不属于当前部门");
        }
        List<Long> added = wanted.stream().filter(u -> !before.contains(u)).toList();
        List<Long> removed = before.stream().filter(u -> !wanted.contains(u)).toList();
        if (!removed.isEmpty()) {
            userRoles.deleteRoleMembers(role, removed);
        }
        if (!added.isEmpty()) {
            userRoles.addRoleMembers(role, dept, app, added, actor);
        }
        List<Long> changed = new ArrayList<>(added);
        changed.addAll(removed);
        if (!changed.isEmpty()) {
            contexts.revokeDomainForUsers(changed, dept, app);
        }
        cache.touchDomain(dept, app);
        webhooks.emit(app, "user.roles.changed", Map.of("roleId", role, "departmentId", dept,
                "added", added.size(), "removed", removed.size()));
        String roleCode = roleCode(role);
        for (long user : added) {
            audit.audit(actor, "ROLE_MEMBERS_UPDATED", "user:" + user, dept, app, "新增角色 " + roleCode);
        }
        for (long user : removed) {
            audit.audit(actor, "ROLE_MEMBERS_UPDATED", "user:" + user, dept, app, "移除角色 " + roleCode);
        }
    }

    @Override
    public UserRoleAssignmentsResult assignments(long user, long dept, long app) {
        domain(dept, app);
        List<Long> roleIds = new ArrayList<>();
        Map<Long, Instant> timed = new LinkedHashMap<>();
        for (UserRoleRepository.RoleExpiry e : userRoles.findExpiriesByDomain(user, dept, app)) {
            roleIds.add(e.getRoleId());
            if (e.getExpiresAt() != null) {
                timed.put(e.getRoleId(), e.getExpiresAt());
            }
        }
        return new UserRoleAssignmentsResult(List.copyOf(roleIds), timed);
    }

    @Override
    public PageResult<DomainAssignmentResult> domainAssignments(long dept, long app, String keyword, int page, int size) {
        domain(dept, app);
        Page<MembershipRepository.MemberRow> result = memberships.findDepartmentMembersPage(dept, keyword, page, size);
        List<Long> userIds = result.getContent().stream().map(MembershipRepository.MemberRow::id).toList();
        Map<Long, List<Long>> roleIdsByUser = new HashMap<>();
        Map<Long, Map<Long, Instant>> expiriesByUser = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (UserRoleRepository.DomainRolePair pair : userRoles.findDomainRolePairs(dept, app, userIds)) {
                roleIdsByUser.computeIfAbsent(pair.getUserId(), k -> new ArrayList<>()).add(pair.getRoleId());
                if (pair.getExpiresAt() != null) {
                    expiriesByUser.computeIfAbsent(pair.getUserId(), k -> new LinkedHashMap<>())
                            .put(pair.getRoleId(), pair.getExpiresAt());
                }
            }
        }
        List<DomainAssignmentResult> items = result.getContent().stream()
                .map(m -> new DomainAssignmentResult(m.id(), m.name(), m.externalId(), m.email(), m.enabled(),
                        roleIdsByUser.getOrDefault(m.id(), List.of()),
                        expiriesByUser.getOrDefault(m.id(), Map.of())))
                .toList();
        return PageResult.of(items, result.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public BatchUserRolesResult addUserRoles(long dept, long app, BatchUserRolesCommand body, String actor) {
        domain(dept, app);
        Set<Long> userIds = new TreeSet<>(body.userIds());
        Set<Long> roleIds = new TreeSet<>(body.roleIds());
        if (users.lockIdsByIdIn(userIds).size() != userIds.size()) {
            throw ApiException.missing("用户不存在");
        }
        if (memberships.countLocked(userIds, dept) != userIds.size()) {
            throw ApiException.forbidden("用户不属于当前部门");
        }
        for (long role : roleIds) {
            if (!roles.lockById(role)) {
                throw ApiException.missing("角色不存在");
            }
            requireRoleInDomain(role, dept, app);
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = body.expiresInDays() == null ? null : now.plus(body.expiresInDays(), ChronoUnit.DAYS);
        int changedUsers = 0;
        int grants = 0;
        for (long user : userIds) {
            Map<Long, Instant> current = userRoles.findLockedAssignments(user, dept, app);
            List<Long> added = new ArrayList<>();
            for (long role : roleIds) {
                Instant held = current.get(role);
                if (current.containsKey(role) && (held == null || held.isAfter(now))) {
                    continue;
                }
                userRoles.grantRole(user, dept, app, role, expires, actor);
                added.add(role);
            }
            if (added.isEmpty()) {
                continue;
            }
            String external = users.findSummaryById(user).orElseThrow().getExternalId();
            contexts.revokeDomain(user, dept, app);
            cache.touchUser(user);
            webhooks.emit(app, "user.roles.changed", Map.of("userId", user, "externalId", external, "departmentId", dept));
            audit.audit(actor, "USER_ROLES_UPDATED", external, dept, app,
                    "批量追加 " + String.join(", ", roles.findCodesByIdIn(added)));
            changedUsers++;
            grants += added.size();
        }
        return new BatchUserRolesResult(userIds.size(), changedUsers, grants);
    }

    @Override
    @Transactional
    public void userRoles(long user, long dept, long app, List<Long> roleIds, String actor) {
        userRoles(user, dept, app, roleIds, null, null, actor);
    }

    @Override
    @Transactional
    public void userRoles(long user, long dept, long app, List<Long> roleIds, Integer expiresInDays, String actor) {
        userRoles(user, dept, app, roleIds, expiresInDays, null, actor);
    }

    @Override
    @Transactional
    public void userRoles(long user, long dept, long app, List<Long> roleIds, Integer expiresInDays,
                          List<RoleExpiryCommand> expiries, String actor) {
        domain(dept, app);
        String external = users.lockExternalIdById(user).orElseThrow(() -> ApiException.missing("用户不存在"));
        if (!memberships.existsLocked(user, dept)) {
            throw ApiException.forbidden("用户不属于当前部门");
        }
        Set<Long> wanted = new LinkedHashSet<>(roleIds);
        if (!wanted.isEmpty() && roles.countByDepartmentIdAndApplicationIdAndIdIn(dept, app, wanted) != wanted.size()) {
            throw ApiException.forbidden("关联记录不属于当前隔离域");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<Long, Instant> explicit = new HashMap<>();
        for (RoleExpiryCommand entry : expiries == null ? List.<RoleExpiryCommand>of() : expiries) {
            if (!wanted.contains(entry.roleId())) {
                throw ApiException.bad("只能为本次分配的角色设置到期时间");
            }
            explicit.put(entry.roleId(), expiry(entry.expiresAt(), now));
        }
        Instant uniform = expiresInDays == null ? null : now.plus(expiresInDays, ChronoUnit.DAYS);
        Map<Long, Instant> current = userRoles.findLockedAssignments(user, dept, app);
        List<Long> removed = current.keySet().stream().filter(role -> !wanted.contains(role)).toList();
        if (!removed.isEmpty()) {
            userRoles.deleteByUserIdAndRoleIdIn(user, removed);
        }
        int expiryChanges = 0;
        for (long role : wanted) {
            boolean held = current.containsKey(role);
            Instant target;
            if (explicit.containsKey(role)) {
                target = explicit.get(role);
            } else if (uniform != null) {
                target = uniform;
            } else if (held) {
                continue;
            } else {
                target = null;
            }
            Instant was = current.get(role);
            if (held && Objects.equals(was, target)) {
                continue;
            }
            userRoles.grantRole(user, dept, app, role, target, actor);
            if (held) {
                expiryChanges++;
            }
        }
        contexts.revokeDomain(user, dept, app);
        cache.touchUser(user);
        webhooks.emit(app, "user.roles.changed", Map.of("userId", user, "externalId", external, "departmentId", dept));
        String detail = roleDiff(current.keySet(), wanted);
        if (expiryChanges > 0) {
            detail += "；调整到期时间 " + expiryChanges + " 个";
        }
        if (uniform != null) {
            detail += "，" + expiresInDays + " 天后到期";
        }
        audit.audit(actor, "USER_ROLES_UPDATED", external, dept, app, detail);
    }

    @Override
    @Transactional
    public DomainCopyResult copyDomain(DomainCopyCommand body, String actor) {
        long app = body.applicationId();
        long source = body.sourceDepartmentId();
        long target = body.targetDepartmentId();
        if (source == target) {
            throw ApiException.bad("源部门与目标部门不能相同");
        }
        domain(source, app);
        domain(target, app);
        Map<String, Long> existingPermissions = new HashMap<>();
        for (PermissionRepository.PermissionSummary p : permissions.findSummariesByDomain(target, app)) {
            existingPermissions.put(p.getCode(), p.getId());
        }
        Map<Long, Long> permissionMap = new HashMap<>();
        int permissionsCreated = 0;
        for (PermissionRepository.PermissionSummary p : permissions.findSummariesByDomain(source, app)) {
            Long mapped = existingPermissions.get(p.getCode());
            if (mapped == null) {
                mapped = permissions.saveAndFlush(new Permission()
                        .setDepartment(departments.getReferenceById(target))
                        .setApplication(applications.getReferenceById(app))
                        .setName(p.getName()).setCode(p.getCode())
                        .setResource(p.getResource()).setAction(p.getAction())).getId();
                permissionsCreated++;
            }
            permissionMap.put(p.getId(), mapped);
        }
        Set<String> existingRoles = new HashSet<>();
        for (RoleRepository.RoleSummary r : roles.findSummariesByDomain(target, app)) {
            existingRoles.add(r.getCode());
        }
        Map<Long, List<Long>> grants = new HashMap<>();
        for (RolePermissionRepository.RolePermissionPair pair : rolePermissions.findPairsByDomain(source, app)) {
            Long mapped = permissionMap.get(pair.getPermissionId());
            if (mapped != null) {
                grants.computeIfAbsent(pair.getRoleId(), k -> new ArrayList<>()).add(mapped);
            }
        }
        int rolesCreated = 0;
        int rolesSkipped = 0;
        for (RoleRepository.RoleSummary r : roles.findSummariesByDomain(source, app)) {
            if (existingRoles.contains(r.getCode())) {
                rolesSkipped++;
                continue;
            }
            long created = roles.saveAndFlush(new Role()
                    .setDepartment(departments.getReferenceById(target))
                    .setApplication(applications.getReferenceById(app))
                    .setName(r.getName()).setCode(r.getCode())
                    .setDescription(text(r.getDescription())).setEnabled(r.isEnabled())).getId();
            rolePermissions.replaceRolePermissions(created, target, app,
                    new LinkedHashSet<>(grants.getOrDefault(r.getId(), List.of())));
            rolesCreated++;
        }
        int permissionsSkipped = permissionMap.size() - permissionsCreated;
        audit.audit(actor, "DOMAIN_COPIED", "department:" + source + " -> department:" + target, target, app,
                "新建权限 " + permissionsCreated + " 个、角色 " + rolesCreated + " 个；同编码跳过权限 "
                        + permissionsSkipped + " 个、角色 " + rolesSkipped + " 个");
        return new DomainCopyResult(permissionsCreated, permissionsSkipped, rolesCreated, rolesSkipped);
    }

    @Override
    public SimulationResult simulate(SimulationCommand body) {
        long dept = body.departmentId();
        long app = body.applicationId();
        domain(dept, app);
        UserRepository.UserSummary u = users.findSummaryById(body.userId()).orElseThrow(() -> ApiException.missing("用户不存在"));
        UserSummaryResult userSummary = new UserSummaryResult(u.getId(), u.getName(), u.getExternalId(), u.getEmail(),
                u.isEnabled(), u.getActiveDepartmentId(), u.getContextVersion(), u.getCreatedAt());
        List<String> issues = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        if (!u.isEnabled()) {
            issues.add("用户已停用");
        }
        boolean applicationEnabled = applications.existsByIdAndEnabledTrue(app);
        if (!applicationEnabled) {
            issues.add("软件已停用");
        }
        boolean member = memberships.existsByUserIdAndDepartmentId(body.userId(), dept);
        if (!member) {
            issues.add("用户不属于该部门");
        }
        Instant now = Instant.now();
        List<UserRoleRepository.DomainUserRole> assigned = userRoles.findUserRolesInDomain(body.userId(), dept, app);
        Set<Long> usable = new LinkedHashSet<>();
        for (UserRoleRepository.DomainUserRole role : assigned) {
            boolean expired = role.getExpiresAt() != null && role.getExpiresAt().isBefore(now);
            if (role.isEnabled() && !expired) {
                usable.add(role.getId());
            } else if (expired) {
                notices.add("角色 " + role.getCode() + " 的授权已到期，不计入权限");
            } else {
                notices.add("角色 " + role.getCode() + " 已停用，不计入权限");
            }
        }
        Set<Long> active = new LinkedHashSet<>();
        if (body.activeRoleIds() == null) {
            active.addAll(usable);
        } else {
            for (long role : body.activeRoleIds()) {
                if (usable.contains(role)) {
                    active.add(role);
                } else {
                    issues.add("角色 " + role + " 不是该用户在此隔离域内已分配且启用的角色，业务系统创建上下文会被拒绝");
                }
            }
        }
        List<SimulatedRoleResult> roleViews = assigned.stream().map(role -> {
            boolean expired = role.getExpiresAt() != null && role.getExpiresAt().isBefore(now);
            return new SimulatedRoleResult(role.getId(), role.getName(), role.getCode(), role.isEnabled(),
                    role.getExpiresAt(), expired, active.contains(role.getId()));
        }).toList();
        Map<String, RolePermissionRepository.GrantProjection> firstGrantByCode = new LinkedHashMap<>();
        Map<String, LinkedHashSet<Long>> grantedByCode = new LinkedHashMap<>();
        Map<Long, List<Long>> directSources = roleInheritances.mapExpandedToDirectActiveRoles(active, dept, app);
        if (!directSources.isEmpty()) {
            for (RolePermissionRepository.GrantProjection grant : rolePermissions.findGrantsByRoleIds(directSources.keySet())) {
                firstGrantByCode.putIfAbsent(grant.getCode(), grant);
                grantedByCode.computeIfAbsent(grant.getCode(), k -> new LinkedHashSet<>())
                        .addAll(directSources.getOrDefault(grant.getRoleId(), List.of(grant.getRoleId())));
            }
        }
        List<SimulatedPermissionResult> permissionViews = firstGrantByCode.values().stream()
                .map(g -> new SimulatedPermissionResult(g.getId(), g.getCode(), g.getName(), g.getResource(),
                        g.getAction(), List.copyOf(grantedByCode.get(g.getCode()))))
                .toList();
        boolean eligible = issues.isEmpty();
        Map<String, Boolean> decisions = null;
        Boolean allowed = null;
        if (body.permissions() != null && !body.permissions().isEmpty()) {
            decisions = new LinkedHashMap<>();
            for (String code : body.permissions()) {
                decisions.put(code, eligible && firstGrantByCode.containsKey(code));
            }
            allowed = decisions.values().stream().allMatch(Boolean::booleanValue);
        }
        return new SimulationResult(
                eligible, issues, notices, userSummary, member, applicationEnabled,
                u.getActiveDepartmentId() == null || u.getActiveDepartmentId() != dept,
                roleViews, eligible ? permissionViews : List.of(), decisions, allowed);
    }

    @Override
    public DomainOverviewResult overview(long dept, long app) {
        domain(dept, app);
        return new DomainOverviewResult(
                applications.count(),
                applications.countByEnabledTrue(),
                departments.count(),
                users.count(),
                users.countByEnabledTrue(),
                memberships.countByDepartmentId(dept),
                roles.countByDepartmentIdAndApplicationId(dept, app),
                permissions.countByDepartmentIdAndApplicationId(dept, app),
                userRoles.countDistinctUsersInDomain(dept, app),
                applicationKeys.countActiveByApplicationId(app),
                contexts.countActiveInDomain(dept, app),
                auditEvents.countByCreatedAtAfter(Instant.now().minus(24, ChronoUnit.HOURS)));
    }

    private static Instant expiry(OffsetDateTime value, Instant now) {
        if (value == null) {
            return null;
        }
        Instant at = value.toInstant().truncatedTo(ChronoUnit.SECONDS);
        if (!at.isAfter(now)) {
            throw ApiException.bad("到期时间必须晚于当前时间");
        }
        if (at.isAfter(now.plus(3650, ChronoUnit.DAYS))) {
            throw ApiException.bad("到期时间不能晚于 3650 天后");
        }
        return at;
    }

    private String roleCode(long id) {
        return roles.findCodesByIdIn(List.of(id)).stream().findFirst().orElse("#" + id);
    }

    private String permissionCode(long id) {
        return permissions.findCodesByIdIn(List.of(id)).stream().findFirst().orElse("#" + id);
    }

    private String roleDiff(Set<Long> before, Set<Long> after) {
        List<Long> added = after.stream().filter(id -> !before.contains(id)).toList();
        List<Long> removed = before.stream().filter(id -> !after.contains(id)).toList();
        return formatDiff(added.isEmpty() ? List.of() : roles.findCodesByIdIn(added),
                removed.isEmpty() ? List.of() : roles.findCodesByIdIn(removed));
    }

    private String permissionDiff(Set<Long> before, Set<Long> after) {
        List<Long> added = after.stream().filter(id -> !before.contains(id)).toList();
        List<Long> removed = before.stream().filter(id -> !after.contains(id)).toList();
        return formatDiff(added.isEmpty() ? List.of() : permissions.findCodesByIdIn(added),
                removed.isEmpty() ? List.of() : permissions.findCodesByIdIn(removed));
    }

    private static String formatDiff(List<String> added, List<String> removed) {
        if (added.isEmpty() && removed.isEmpty()) {
            return "未变化";
        }
        List<String> parts = new ArrayList<>();
        if (!added.isEmpty()) parts.add("新增 " + String.join(", ", added));
        if (!removed.isEmpty()) parts.add("移除 " + String.join(", ", removed));
        return String.join("；", parts);
    }

    private static String text(String value) {
        return Objects.requireNonNullElse(value, "").strip();
    }
}
