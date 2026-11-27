package com.melonhub.rbac.service.port.domainrbac;

import com.melonhub.rbac.service.port.organization.OrganizationModels.UserSummaryResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 隔离域（部门 × 软件）内 RBAC1 角色、权限、继承、授权分配、跨部门复制与权限诊断模型。
 */
public final class DomainRbacModels {
    private DomainRbacModels() {}

    public record SaveRoleCommand(String name, String code, String description) {}

    public record SavePermissionCommand(String name, String code, String resource, String action) {}

    public record RoleExpiryCommand(long roleId, OffsetDateTime expiresAt) {}

    public record BatchUserRolesCommand(List<Long> userIds, List<Long> roleIds, Integer expiresInDays) {}

    public record DomainCopyCommand(long applicationId, long sourceDepartmentId, long targetDepartmentId) {}

    public record SimulationCommand(long userId, long departmentId, long applicationId,
                                    List<Long> activeRoleIds, List<String> permissions) {}

    public record RoleMemberResult(long id, String name, String externalId, String email, boolean enabled) {}

    public record RoleResult(
            long id,
            String name,
            String code,
            String description,
            boolean enabled,
            Instant createdAt,
            long memberCount,
            List<Long> permissionIds,
            List<Long> parentRoleIds
    ) {}

    public record PermissionResult(
            long id,
            String name,
            String code,
            String resource,
            String action,
            Instant createdAt,
            long roleCount
    ) {}

    public record UserRoleAssignmentsResult(List<Long> roleIds, Map<Long, Instant> roleExpiries) {}

    public record DomainAssignmentResult(
            long id,
            String name,
            String externalId,
            String email,
            boolean enabled,
            List<Long> roleIds,
            Map<Long, Instant> roleExpiries
    ) {}

    public record BatchUserRolesResult(int users, int changedUsers, int grantsAdded) {}

    public record DomainCopyResult(int permissionsCreated, int permissionsSkipped, int rolesCreated, int rolesSkipped) {}

    public record SimulatedRoleResult(
            long id,
            String name,
            String code,
            boolean enabled,
            Instant expiresAt,
            boolean expired,
            boolean active
    ) {}

    public record SimulatedPermissionResult(
            long id,
            String code,
            String name,
            String resource,
            String action,
            List<Long> grantedBy
    ) {}

    public record SimulationResult(
            boolean eligible,
            List<String> issues,
            List<String> notices,
            UserSummaryResult user,
            boolean member,
            boolean applicationEnabled,
            boolean switchesDepartment,
            List<SimulatedRoleResult> roles,
            List<SimulatedPermissionResult> permissions,
            Map<String, Boolean> decisions,
            Boolean allowed
    ) {}

    public record DomainOverviewResult(
            long applications,
            long enabledApplications,
            long departments,
            long users,
            long enabledUsers,
            long departmentMembers,
            long roles,
            long permissions,
            long members,
            long activeKeys,
            long activeContexts,
            long auditLast24h
    ) {}
}
