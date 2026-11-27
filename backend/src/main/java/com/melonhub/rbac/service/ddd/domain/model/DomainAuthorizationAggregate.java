package com.melonhub.rbac.service.ddd.domain.model;

import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.GrantDuration;
import com.melonhub.rbac.service.ddd.domain.value.IsolationDomain;
import java.time.Instant;
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

/**
 * 隔离域授权聚合根（部门 × 软件）：封装角色-权限同域约束、用户角色替换与批量追加规划、
 * 角色成员集合差异与并发新成员检测、跨部门配置复制规划，以及强类型的有效角色/权限诊断与自查引擎。
 */
public final class DomainAuthorizationAggregate {
    public record PermissionSpec(long id, String name, String code, String resource, String action) {}

    public record RoleSpec(long id, String name, String code, String description, boolean enabled) {}

    public record AssignedDomainRole(long id, String name, String code, boolean enabled, Instant expiresAt) {
        public boolean isExpiredAt(Instant now) {
            return expiresAt != null && expiresAt.isBefore(now);
        }

        public boolean isEffectiveAt(Instant now) {
            return enabled && !isExpiredAt(now);
        }
    }

    public record PermissionGrant(long permissionId, String code, String name, String resource, String action, long roleId) {}

    public record GrantChange(long roleId, Instant expiresAt, boolean existingRoleExpiryChanged) {}

    public record RoleReplacementPlan(
            List<Long> removedRoleIds,
            List<GrantChange> grantsToApply,
            int expiryChanges,
            Set<Long> finalRoleIds
    ) {}

    public record BatchAdditionPlan(
            Map<Long, List<Long>> rolesToAddByUser,
            Instant sharedExpiry,
            int changedUsers,
            int grantsAdded
    ) {}

    public record MemberSyncPlan(
            Set<Long> lateMembers,
            List<Long> added,
            List<Long> removed
    ) {
        public boolean requiresRetry() { return !lateMembers.isEmpty(); }
    }

    public record DomainCopyPlan(
            List<PermissionSpec> permissionsToCreate,
            Map<Long, Long> existingPermissionMap,
            List<RoleCopyItem> rolesToCreate,
            int permissionsSkipped,
            int rolesSkipped
    ) {}

    public record RoleCopyItem(RoleSpec sourceRole, List<Long> sourcePermissionIds) {}

    public record SelfAccessRole(long id, String name, String code, boolean enabled, Instant expiresAt) {}

    public record SelfAccessPreparation(List<Long> usableRoleIds, List<SelfAccessRole> roles) {}

    public record SelfAccessReport(long userId, long departmentId, long applicationId, List<SelfAccessRole> roles, List<String> permissions) {}

    public record SimulatedRole(long id, String name, String code, boolean enabled, Instant expiresAt, boolean expired, boolean active) {}

    public record AggregatedPermission(long id, String code, String name, String resource, String action, List<Long> grantedBy) {}

    public record DomainRoleAssessment(
            List<String> issues,
            List<String> notices,
            Set<Long> usableRoleIds,
            Set<Long> activeRoleIds,
            List<SimulatedRole> roles
    ) {}

    public record SimulationOutcome(
            boolean eligible,
            List<String> issues,
            List<String> notices,
            boolean member,
            boolean applicationEnabled,
            boolean switchesDepartment,
            List<SimulatedRole> roles,
            List<AggregatedPermission> permissions,
            Map<String, Boolean> decisions,
            Boolean allowed
    ) {}

    private final IsolationDomain domain;

    public DomainAuthorizationAggregate(IsolationDomain domain) {
        this.domain = domain;
    }

    public IsolationDomain domain() { return domain; }

    public record DepartmentUsage(long members, long roles, long permissions, long admins) {}

    public record ApplicationUsage(long roles, long permissions) {}

    public static void assertDepartmentDeletable(DepartmentUsage usage) {
        var parts = new ArrayList<String>();
        if (usage.members() > 0) parts.add(usage.members() + " 名成员");
        if (usage.roles() > 0) parts.add(usage.roles() + " 个角色");
        if (usage.permissions() > 0) parts.add(usage.permissions() + " 个权限");
        if (usage.admins() > 0) parts.add(usage.admins() + " 名限定于该部门的管理员");
        if (!parts.isEmpty()) {
            throw DomainException.conflict("部门下还有 " + String.join("、", parts) + "，请先移除后再删除");
        }
    }

    public static void assertApplicationDeletable(ApplicationUsage usage) {
        var parts = new ArrayList<String>();
        if (usage.roles() > 0) parts.add(usage.roles() + " 个角色");
        if (usage.permissions() > 0) parts.add(usage.permissions() + " 个权限");
        if (!parts.isEmpty()) {
            throw DomainException.conflict("软件下还有 " + String.join("、", parts) + "，请先删除，或改为停用");
        }
    }

    public RoleReplacementPlan planUserRoleReplacement(
            Collection<Long> requestedRoles,
            Map<Long, Instant> currentExpiries,
            Integer expiresInDays,
            List<GrantDuration.RoleExpiryOverride> overrides,
            Instant now
    ) {
        Instant base = now.truncatedTo(ChronoUnit.SECONDS);
        var wanted = new LinkedHashSet<>(requestedRoles);
        var explicit = GrantDuration.resolveExplicitMap(wanted, overrides, base);
        Instant uniform = GrantDuration.fromDays(expiresInDays, base);
        var removed = currentExpiries.keySet().stream().filter(role -> !wanted.contains(role)).toList();
        var grants = new ArrayList<GrantChange>();
        int expiryChanges = 0;
        for (long role : wanted) {
            boolean held = currentExpiries.containsKey(role);
            Instant target;
            if (explicit.containsKey(role)) target = explicit.get(role);
            else if (uniform != null) target = uniform;
            else if (held) continue;
            else target = null;
            Instant was = currentExpiries.get(role);
            if (held && Objects.equals(was, target)) continue;
            grants.add(new GrantChange(role, target, held));
            if (held) expiryChanges++;
        }
        return new RoleReplacementPlan(removed, grants, expiryChanges, wanted);
    }

    public BatchAdditionPlan planBatchRoleAdditions(
            Collection<Long> orderedUsers,
            Collection<Long> orderedRoles,
            Map<Long, Map<Long, Instant>> currentAssignmentsByUser,
            Integer expiresInDays,
            Instant fixedNow
    ) {
        Instant base = fixedNow.truncatedTo(ChronoUnit.SECONDS);
        Instant expires = GrantDuration.fromDays(expiresInDays, base);
        var toAddByUser = new LinkedHashMap<Long, List<Long>>();
        int changedUsers = 0, grantsAdded = 0;
        for (long userId : orderedUsers) {
            var current = currentAssignmentsByUser.getOrDefault(userId, Map.of());
            var added = new ArrayList<Long>();
            for (long roleId : orderedRoles) {
                Instant held = current.get(roleId);
                if (current.containsKey(roleId) && (held == null || held.isAfter(base))) continue;
                added.add(roleId);
            }
            if (!added.isEmpty()) {
                toAddByUser.put(userId, added);
                changedUsers++;
                grantsAdded += added.size();
            }
        }
        return new BatchAdditionPlan(toAddByUser, expires, changedUsers, grantsAdded);
    }

    public MemberSyncPlan planRoleMemberReplacement(
            Set<Long> wantedUsers,
            Set<Long> lockedUsers,
            Set<Long> currentMembers
    ) {
        var late = new TreeSet<Long>(currentMembers);
        late.removeAll(lockedUsers);
        if (!late.isEmpty()) {
            return new MemberSyncPlan(late, List.of(), List.of());
        }
        var added = wantedUsers.stream().filter(u -> !currentMembers.contains(u)).toList();
        var removed = currentMembers.stream().filter(u -> !wantedUsers.contains(u)).toList();
        return new MemberSyncPlan(Set.of(), added, removed);
    }

    public DomainCopyPlan planCopyTo(
            long targetDepartmentId,
            List<PermissionSpec> sourcePermissions,
            List<PermissionSpec> targetPermissions,
            List<RoleSpec> sourceRoles,
            List<RoleSpec> targetRoles,
            Map<Long, List<Long>> sourceRolePermissionIds
    ) {
        if (domain.departmentId() == targetDepartmentId) {
            throw DomainException.bad("源部门与目标部门不能相同");
        }
        var targetPermByCode = new HashMap<String, Long>();
        for (var p : targetPermissions) targetPermByCode.put(p.code(), p.id());
        var permsToCreate = new ArrayList<PermissionSpec>();
        var existingMap = new HashMap<Long, Long>();
        for (var p : sourcePermissions) {
            Long mapped = targetPermByCode.get(p.code());
            if (mapped == null) permsToCreate.add(p);
            else existingMap.put(p.id(), mapped);
        }
        var existingRoleCodes = new HashSet<String>();
        for (var r : targetRoles) existingRoleCodes.add(r.code());
        var rolesToCreate = new ArrayList<RoleCopyItem>();
        int rolesSkipped = 0;
        for (var r : sourceRoles) {
            if (existingRoleCodes.contains(r.code())) {
                rolesSkipped++;
            } else {
                rolesToCreate.add(new RoleCopyItem(r, sourceRolePermissionIds.getOrDefault(r.id(), List.of())));
            }
        }
        return new DomainCopyPlan(permsToCreate, existingMap, rolesToCreate, existingMap.size(), rolesSkipped);
    }

    public SelfAccessPreparation prepareSelfAccess(List<AssignedDomainRole> assignedRoles, Instant now) {
        var usable = new ArrayList<Long>();
        var views = new ArrayList<SelfAccessRole>();
        for (var role : assignedRoles) {
            if (role.isEffectiveAt(now)) usable.add(role.id());
            views.add(new SelfAccessRole(role.id(), role.name(), role.code(), role.enabled(), role.expiresAt()));
        }
        return new SelfAccessPreparation(usable, views);
    }

    public SelfAccessReport buildSelfAccessReport(long userId, SelfAccessPreparation prep, List<PermissionGrant> grants) {
        var permissionCodes = new LinkedHashSet<String>();
        for (var grant : grants) permissionCodes.add(grant.code());
        return new SelfAccessReport(userId, domain.departmentId(), domain.applicationId(), prep.roles(), new ArrayList<>(permissionCodes));
    }

    public DomainRoleAssessment assessSimulationRoles(
            boolean userEnabled,
            boolean applicationEnabled,
            boolean member,
            List<AssignedDomainRole> assignedRoles,
            Collection<Long> requestedActiveRoleIds,
            Instant now
    ) {
        var issues = new ArrayList<String>();
        var notices = new ArrayList<String>();
        if (!userEnabled) issues.add("用户已停用");
        if (!applicationEnabled) issues.add("软件已停用");
        if (!member) issues.add("用户不属于该部门");

        var usable = new LinkedHashSet<Long>();
        for (var role : assignedRoles) {
            boolean expired = role.isExpiredAt(now);
            if (role.enabled() && !expired) {
                usable.add(role.id());
            } else if (expired) {
                notices.add("角色 " + role.code() + " 的授权已到期，不计入权限");
            } else {
                notices.add("角色 " + role.code() + " 已停用，不计入权限");
            }
        }
        var active = new LinkedHashSet<Long>();
        if (requestedActiveRoleIds == null) {
            active.addAll(usable);
        } else {
            for (long roleId : requestedActiveRoleIds) {
                if (usable.contains(roleId)) {
                    active.add(roleId);
                } else {
                    issues.add("角色 " + roleId + " 不是该用户在此隔离域内已分配且启用的角色，业务系统创建上下文会被拒绝");
                }
            }
        }
        var roles = new ArrayList<SimulatedRole>();
        for (var role : assignedRoles) {
            roles.add(new SimulatedRole(role.id(), role.name(), role.code(), role.enabled(),
                    role.expiresAt(), role.isExpiredAt(now), active.contains(role.id())));
        }
        return new DomainRoleAssessment(issues, notices, usable, active, roles);
    }

    public SimulationOutcome buildSimulationOutcome(
            Long activeDepartmentId,
            boolean applicationEnabled,
            boolean member,
            DomainRoleAssessment assessment,
            List<PermissionGrant> activeGrants,
            List<String> requestedPermissions
    ) {
        var permissions = aggregateGrants(activeGrants);
        boolean eligible = assessment.issues().isEmpty();
        Map<String, Boolean> decisions = null;
        Boolean allowed = null;
        if (requestedPermissions != null && !requestedPermissions.isEmpty()) {
            decisions = new LinkedHashMap<>();
            for (String code : requestedPermissions) {
                decisions.put(code, eligible && permissions.containsKey(code));
            }
            allowed = decisions.values().stream().allMatch(Boolean::booleanValue);
        }
        return new SimulationOutcome(
                eligible,
                assessment.issues(),
                assessment.notices(),
                member,
                applicationEnabled,
                activeDepartmentId == null || activeDepartmentId != domain.departmentId(),
                assessment.roles(),
                eligible ? new ArrayList<>(permissions.values()) : List.of(),
                decisions,
                allowed);
    }

    public static String describeSetDiff(Collection<String> added, Collection<String> removed) {
        if (added.isEmpty() && removed.isEmpty()) return "未变化";
        var parts = new ArrayList<String>();
        if (!added.isEmpty()) parts.add("新增 " + String.join(", ", added));
        if (!removed.isEmpty()) parts.add("移除 " + String.join(", ", removed));
        return String.join("；", parts);
    }

    private static Map<String, AggregatedPermission> aggregateGrants(List<PermissionGrant> grants) {
        var roleIdsByCode = new LinkedHashMap<String, LinkedHashSet<Long>>();
        var firstByCode = new LinkedHashMap<String, PermissionGrant>();
        for (var grant : grants) {
            firstByCode.putIfAbsent(grant.code(), grant);
            roleIdsByCode.computeIfAbsent(grant.code(), k -> new LinkedHashSet<>()).add(grant.roleId());
        }
        var permissions = new LinkedHashMap<String, AggregatedPermission>();
        firstByCode.forEach((code, g) -> permissions.put(code,
                new AggregatedPermission(g.permissionId(), code, g.name(), g.resource(), g.action(), List.copyOf(roleIdsByCode.get(code)))));
        return permissions;
    }
}
