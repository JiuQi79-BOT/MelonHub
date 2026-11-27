package com.melonhub.rbac.api.admin;

import com.melonhub.rbac.api.admin.vo.AdminAccountVO;
import com.melonhub.rbac.api.admin.vo.AdminFunctionVO;
import com.melonhub.rbac.api.admin.vo.AdminRoleVO;
import com.melonhub.rbac.api.admin.vo.LoginVO;
import com.melonhub.rbac.api.admin.vo.MfaSetupVO;
import com.melonhub.rbac.api.admin.vo.PrincipalVO;
import com.melonhub.rbac.service.port.admin.AdminModels.AdminAccountResult;
import com.melonhub.rbac.service.port.admin.AdminModels.AdminFunctionResult;
import com.melonhub.rbac.service.port.admin.AdminModels.AdminRoleResult;
import com.melonhub.rbac.service.port.admin.AdminModels.LoginResult;
import com.melonhub.rbac.service.port.admin.AdminModels.MfaSetupResult;
import com.melonhub.rbac.service.port.admin.AdminModels.PrincipalResult;

/**
 * 控制台管理员认证与角色账号模块 Result 与 VO 映射器。
 */
public final class AdminVoMapper {
    private AdminVoMapper() {}

    public static PrincipalVO toPrincipalVO(PrincipalResult r) {
        return new PrincipalVO(r.id(), r.username(), r.displayName(), r.role(), r.roleName(),
                r.functions(), r.root(), r.kind(), r.allDepartments(), r.departmentIds(),
                r.allApplications(), r.applicationIds(), r.mfaEnabled());
    }

    public static LoginVO toLoginVO(LoginResult r) {
        return new LoginVO(r.token(), r.expiresIn(), r.idleTimeout(), r.id(), r.username(), r.displayName(),
                r.role(), r.roleName(), r.functions(), r.root(), r.kind(), r.allDepartments(),
                r.departmentIds(), r.allApplications(), r.applicationIds(), r.mfaEnabled());
    }

    public static MfaSetupVO toMfaSetupVO(MfaSetupResult r) {
        return new MfaSetupVO(r.secret(), r.uri());
    }

    public static AdminAccountVO toAdminAccountVO(AdminAccountResult r) {
        return new AdminAccountVO(r.id(), r.username(), r.displayName(), r.role(), r.enabled(),
                r.failedAttempts(), r.locked(), r.lockedUntil(), r.lastLoginAt(), r.passwordChangedAt(),
                r.createdAt(), r.activeSessions(), r.mfaEnabled(), r.allApplications(), r.parentId(),
                r.parentUsername(), r.kind(), r.allDepartments(), r.applicationIds(), r.departmentIds(),
                r.manageable());
    }

    public static AdminFunctionVO toAdminFunctionVO(AdminFunctionResult r) {
        return new AdminFunctionVO(r.code(), r.name(), r.category());
    }

    public static AdminRoleVO toAdminRoleVO(AdminRoleResult r) {
        return new AdminRoleVO(r.code(), r.name(), r.description(), r.builtin(), r.functions(),
                r.accountCount(), r.createdAt());
    }
}
