package com.melonhub.rbac.service.port.identity;

import com.melonhub.rbac.service.port.identity.IdentityModels.CourseAuditEventResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.CreateUserWithPasswordCommand;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityApiPermissionCheckResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityCurrentUserResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityDepartmentResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityDepartmentUserResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityLoginResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityPageResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityPermissionCheckResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityUserLookupResult;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveUserCommand;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 面向课程管理系统的统一身份认证、权限判定、组织架构与课程审计日志用例端口。
 */
public interface IdentityUseCase {
    IdentityLoginResult login(String username, String password, List<String> systems);

    IdentityPageResult<CourseAuditEventResult> auditLogs(String authorization, String actor, Long actorId, String target,
                                                         OffsetDateTime from, OffsetDateTime to, int page, int pageSize);

    void logout(String authorization);

    IdentityCurrentUserResult currentUser(String authorization);

    IdentityPageResult<IdentityDepartmentResult> departments(String authorization, String keyword, Long parentId,
                                                             int page, int pageSize);

    IdentityPageResult<IdentityDepartmentUserResult> departmentUsers(String authorization, long departmentId,
                                                                     String keyword, int page, int pageSize);

    IdentityPermissionCheckResult checkPermission(String authorization, String permission);

    IdentityApiPermissionCheckResult checkApiPermission(String authorization, String operationId);

    IdentityUserLookupResult lookupUser(String authorization, String userId);

    long provisionUser(SaveUserCommand body, String password, String actor);

    long createUser(CreateUserWithPasswordCommand body, AdminPrincipal principal);

    void setUserPassword(long userId, String password, AdminPrincipal principal);
}
