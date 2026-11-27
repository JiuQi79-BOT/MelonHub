package com.melonhub.rbac.service.port.admin;

import java.time.Instant;
import java.util.List;

/**
 * 控制台管理员认证、MFA、层级账号树与功能角色模块的应用层命令与结果模型。
 */
public final class AdminModels {
    private AdminModels() {}

    public static final String USERNAME_REGEX = "[a-zA-Z0-9_@-][a-zA-Z0-9_.@-]{2,99}";

    public record LoginCommand(String username, String password, String mfaCode) {
        public LoginCommand(String username, String password) {
            this(username, password, null);
        }
    }

    public record PasswordChangeCommand(String currentPassword, String newPassword) {}

    public record PasswordResetCommand(String password) {}

    public record MfaDisableCommand(String password, String code) {}

    public record CreateAccountCommand(
            String username,
            String displayName,
            String role,
            String password,
            Boolean enabled,
            List<Long> applicationIds,
            Boolean allApplications,
            Long parentId,
            String parentUsername,
            String kind,
            Boolean allDepartments,
            List<Long> departmentIds
    ) {
        public CreateAccountCommand(String username, String displayName, String role, String password,
                                    Boolean enabled, List<Long> applicationIds) {
            this(username, displayName, role, password, enabled, applicationIds, null, null, null, null, null, null);
        }
    }

    public record UpdateAccountCommand(
            String displayName,
            String role,
            Boolean enabled,
            List<Long> applicationIds,
            Boolean allApplications,
            Long parentId,
            String parentUsername,
            String kind,
            Boolean allDepartments,
            List<Long> departmentIds
    ) {
        public UpdateAccountCommand(String displayName, String role, Boolean enabled, List<Long> applicationIds) {
            this(displayName, role, enabled, applicationIds, null, null, null, null, null, null);
        }
    }

    public record CreateAdminRoleCommand(String code, String name, String description, List<String> functions) {}

    public record UpdateAdminRoleCommand(String name, String description, List<String> functions) {}

    public record PrincipalResult(
            long id,
            String username,
            String displayName,
            String role,
            String roleName,
            List<String> functions,
            boolean root,
            String kind,
            boolean allDepartments,
            List<Long> departmentIds,
            boolean allApplications,
            List<Long> applicationIds,
            boolean mfaEnabled
    ) {}

    public record LoginResult(
            String token,
            int expiresIn,
            int idleTimeout,
            long id,
            String username,
            String displayName,
            String role,
            String roleName,
            List<String> functions,
            boolean root,
            String kind,
            boolean allDepartments,
            List<Long> departmentIds,
            boolean allApplications,
            List<Long> applicationIds,
            boolean mfaEnabled
    ) {}

    public record MfaSetupResult(String secret, String uri) {}

    public record AdminAccountResult(
            long id,
            String username,
            String displayName,
            String role,
            boolean enabled,
            int failedAttempts,
            long locked,
            Instant lockedUntil,
            Instant lastLoginAt,
            Instant passwordChangedAt,
            Instant createdAt,
            long activeSessions,
            long mfaEnabled,
            boolean allApplications,
            Long parentId,
            String parentUsername,
            String kind,
            boolean allDepartments,
            List<Long> applicationIds,
            List<Long> departmentIds,
            boolean manageable
    ) {}

    public record AdminFunctionResult(String code, String name, String category) {}

    public record AdminRoleResult(
            String code,
            String name,
            String description,
            boolean builtin,
            List<String> functions,
            long accountCount,
            Instant createdAt
    ) {}
}
