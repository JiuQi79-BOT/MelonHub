package com.melonhub.rbac.service.port.identity;

import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveUserCommand;
import java.util.List;

/**
 * 课程管理系统统一身份认证、权限判定、组织查询与课程审计日志的命令与结果模型。
 */
public final class IdentityModels {
    private IdentityModels() {}

    public record CreateUserWithPasswordCommand(
            String name,
            String externalId,
            String email,
            List<Long> departmentIds,
            String identityPassword
    ) {
        public SaveUserCommand profile() {
            return new SaveUserCommand(name, externalId, email, departmentIds);
        }
    }

    public record IdentityPageResult<T>(int page, int pageSize, long total, List<T> items) {}

    public record IdentityLoginResult(String accessToken, String tokenType, long expiresIn) {}

    public record IdentityCurrentUserResult(
            String subject,
            List<String> roles,
            List<String> permissions,
            String name,
            String no
    ) {}

    public record IdentityDepartmentResult(long id, String code, String name, Long parentId, boolean hasChildren) {}

    public record IdentityDepartmentUserResult(
            String userId,
            String no,
            String name,
            long departmentId,
            String departmentName
    ) {}

    public record IdentityPermissionCheckResult(String permission, boolean allowed) {}

    public record IdentityApiPermissionCheckResult(String operationId, boolean allowed) {}

    public record IdentityUserLookupResult(String userId, String no, String name) {}

    public record CourseAuditActorResult(Long id, String name, List<String> roles, boolean deleted) {}

    public record CourseAuditEventResult(
            long id,
            String at,
            CourseAuditActorResult actor,
            String type,
            String target
    ) {}
}
