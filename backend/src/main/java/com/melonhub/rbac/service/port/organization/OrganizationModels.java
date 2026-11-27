package com.melonhub.rbac.service.port.organization;

import java.time.Instant;
import java.util.List;

/**
 * 组织架构模块（部门与用户）的应用层命令与结果模型。
 */
public final class OrganizationModels {
    private OrganizationModels() {}

    public record SaveDepartmentCommand(String name, String code) {}

    public record SaveUserCommand(String name, String externalId, String email, List<Long> departmentIds) {}

    public record DepartmentResult(
            long id,
            String name,
            String code,
            Instant createdAt,
            long memberCount,
            long roleCount
    ) {}

    public record DepartmentUserResult(long id, String name, String externalId, boolean enabled) {}

    public record UserSummaryResult(
            long id,
            String name,
            String externalId,
            String email,
            boolean enabled,
            Long activeDepartmentId,
            long contextVersion,
            Instant createdAt
    ) {}

    public record UserResult(
            long id,
            String name,
            String externalId,
            String email,
            boolean enabled,
            Long activeDepartmentId,
            long contextVersion,
            Instant createdAt,
            List<Long> departmentIds
    ) {}

    public record AccessDomainRoleResult(
            long id,
            String name,
            String code,
            boolean enabled,
            Instant expiresAt,
            String grantedBy
    ) {}

    public record AccessDomainResult(
            long departmentId,
            String departmentName,
            long applicationId,
            String applicationName,
            boolean applicationEnabled,
            List<AccessDomainRoleResult> roles,
            List<String> permissions
    ) {}

    public record ActiveContextResult(
            long departmentId,
            String departmentName,
            long applicationId,
            String applicationName,
            Instant createdAt,
            Instant expiresAt,
            long activeRoleCount,
            long valid
    ) {}

    public record UserAccessResult(
            UserResult user,
            List<AccessDomainResult> domains,
            List<ActiveContextResult> contexts
    ) {}
}
