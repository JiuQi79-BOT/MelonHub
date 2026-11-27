package com.melonhub.rbac.service.port.governance;

import java.time.Instant;
import java.util.List;

/**
 * 授权治理模块（审批、定期复核、批量用户导入、权限清单与反查报表）的命令与结果模型。
 */
public final class GovernanceModels {
    private GovernanceModels() {}

    public record SubmitAccessRequestCommand(String externalId, long departmentId, long roleId,
                                             String reason, Integer durationDays) {}

    public record ApproveRequestCommand(Integer durationDays, String comment, Boolean permanent) {
        public ApproveRequestCommand(Integer durationDays, String comment) {
            this(durationDays, comment, null);
        }
    }

    public record RejectRequestCommand(String comment) {}

    public record CreateReviewCommand(String name, long applicationId, Long departmentId, Integer dueDays) {}

    public record DecideReviewItemCommand(String decision, String comment) {}

    public record UserImportRowCommand(String name, String externalId, String email, List<String> departmentCodes) {}

    public record UserImportCommand(List<UserImportRowCommand> users) {}

    public record ManifestPermissionCommand(String code, String name, String resource, String action) {}

    public record ManifestRoleCommand(String code, String name, String description, Boolean enabled, List<String> permissions) {}

    public record ManifestDepartmentCommand(String code, List<ManifestPermissionCommand> permissions, List<ManifestRoleCommand> roles) {}

    public record ManifestImportCommand(Integer version, List<ManifestDepartmentCommand> departments) {}

    public record AccessRequestResult(
            long id,
            Long userId,
            String userName,
            String externalId,
            Long departmentId,
            String departmentName,
            long applicationId,
            String applicationName,
            Long roleId,
            String roleName,
            String roleCode,
            Boolean roleEnabled,
            String reason,
            Integer durationDays,
            String status,
            String requestedBy,
            String decidedBy,
            Instant decidedAt,
            String decisionComment,
            Instant grantExpiresAt,
            Instant createdAt
    ) {}

    public record SelfAccessRoleResult(long id, String name, String code, boolean enabled, Instant expiresAt) {}

    public record SelfAccessResult(
            long userId,
            long departmentId,
            long applicationId,
            List<SelfAccessRoleResult> roles,
            List<String> permissions
    ) {}

    public record ReviewSummaryResult(
            long id,
            String name,
            long applicationId,
            String applicationName,
            Long departmentId,
            String departmentName,
            String status,
            Instant dueAt,
            String createdBy,
            Instant createdAt,
            String closedBy,
            Instant closedAt,
            long items,
            long pending,
            long kept,
            long revoked,
            long skipped
    ) {}

    public record ReviewDetailResult(
            long id,
            String name,
            long applicationId,
            String applicationName,
            Long departmentId,
            String departmentName,
            String status,
            Instant dueAt,
            String createdBy,
            Instant createdAt,
            String closedBy,
            Instant closedAt,
            String summary,
            long items,
            long pending,
            long kept,
            long revoked,
            long skipped
    ) {}

    public record ReviewItemResult(
            long id,
            Long userId,
            String userName,
            String externalId,
            Long roleId,
            String roleName,
            String roleCode,
            Long departmentId,
            String departmentName,
            String decision,
            String comment,
            String decidedBy,
            Instant decidedAt,
            boolean applied,
            boolean skipped
    ) {}

    public record UserImportFailureResult(int line, String externalId, String error) {}

    public record UserImportResult(int created, int updated, List<UserImportFailureResult> failed) {}

    public record ManifestApplicationResult(String code, String name) {}

    public record ManifestPermissionResult(String code, String name, String resource, String action) {}

    public record ManifestRoleResult(String code, String name, String description, boolean enabled, List<String> permissions) {}

    public record ManifestDepartmentResult(
            String code,
            String name,
            List<ManifestPermissionResult> permissions,
            List<ManifestRoleResult> roles
    ) {}

    public record PermissionManifestResult(
            int version,
            ManifestApplicationResult application,
            String exportedAt,
            List<ManifestDepartmentResult> departments
    ) {}

    public record ManifestImportResult(int permissionsCreated, int permissionsUpdated, int rolesCreated, int rolesUpdated) {}

    public record PermissionHolderRoleResult(long id, String name, String code, Instant expiresAt) {}

    public record PermissionHolderResult(
            long userId,
            String userName,
            String externalId,
            long departmentId,
            String departmentName,
            List<PermissionHolderRoleResult> roles
    ) {}

    public record PermissionHoldersResult(
            String code,
            long applicationId,
            List<PermissionHolderResult> holders,
            long total,
            boolean truncated
    ) {}

    public record ExpiringAssignmentResult(
            long userId,
            String userName,
            String externalId,
            long roleId,
            String roleName,
            String roleCode,
            long departmentId,
            String departmentName,
            long applicationId,
            String applicationName,
            Instant expiresAt,
            String grantedBy,
            Instant grantedAt
    ) {}
}
