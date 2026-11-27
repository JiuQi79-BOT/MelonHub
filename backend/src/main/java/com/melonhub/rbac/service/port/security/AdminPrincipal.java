package com.melonhub.rbac.service.port.security;

import java.util.Collection;
import java.util.Set;

/**
 * 已认证控制台管理员主体：封装角色功能集合与部门/软件双轴管理范围。
 */
public record AdminPrincipal(
        long id,
        String username,
        String displayName,
        String role,
        String roleName,
        Set<AdminFunction> functions,
        boolean root,
        String kind,
        boolean allDepartments,
        Set<Long> departmentIds,
        boolean allApplications,
        Set<Long> applicationIds
) {
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ADMIN = "ADMIN";
    public static final String APP_ADMIN = "APP_ADMIN";
    public static final String DEPARTMENT_ADMIN = "DEPARTMENT_ADMIN";
    public static final String AUDITOR = "AUDITOR";
    public static final String MANAGER = "MANAGER";
    public static final String DEPARTMENT = "DEPARTMENT";

    public boolean has(AdminFunction function) {
        return functions.contains(function);
    }

    public boolean canUse(AdminFunction function) {
        return has(function)
                && (!function.platformWide() || allDepartments)
                && (!function.platformAdminOnly() || SUPER_ADMIN.equals(role) || ADMIN.equals(role));
    }

    public boolean canAccessApplication(long application) {
        return allApplications || applicationIds.contains(application);
    }

    public boolean canAccessDepartment(long department) {
        return allDepartments || departmentIds.contains(department);
    }

    public Collection<Long> visibleApplications() {
        return allApplications ? null : applicationIds;
    }

    public Collection<Long> visibleDepartments() {
        return allDepartments ? null : departmentIds;
    }

    public Visibility visibility() {
        return new Visibility(visibleApplications(), visibleDepartments());
    }

    public String actor() {
        return username;
    }
}
