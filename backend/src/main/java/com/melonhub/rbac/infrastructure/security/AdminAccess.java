package com.melonhub.rbac.infrastructure.security;

import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;

/**
 * 控制台请求范围校验工具：在控制器入口核对当前管理员的部门与软件管理范围。
 */
public final class AdminAccess {
    public static final String ATTRIBUTE = "melonhub.admin";

    private AdminAccess() {}

    public static AdminPrincipal of(HttpServletRequest request) {
        if (!(request.getAttribute(ATTRIBUTE) instanceof AdminPrincipal principal)) {
            throw ApiException.unauthorized();
        }
        return principal;
    }

    public static AdminPrincipal application(HttpServletRequest request, long application) {
        AdminPrincipal principal = of(request);
        if (!principal.canAccessApplication(application)) {
            throw ApiException.forbidden("当前账号未被授权管理该软件");
        }
        return principal;
    }

    public static AdminPrincipal department(HttpServletRequest request, Long department) {
        AdminPrincipal principal = of(request);
        if (department == null ? !principal.allDepartments() : !principal.canAccessDepartment(department)) {
            throw ApiException.forbidden("当前账号未被授权管理该部门");
        }
        return principal;
    }

    public static AdminPrincipal departments(HttpServletRequest request, Collection<Long> departments) {
        AdminPrincipal principal = of(request);
        if (!principal.allDepartments() && !principal.departmentIds().containsAll(departments)) {
            throw ApiException.forbidden("当前账号未被授权管理所选部门");
        }
        return principal;
    }

    public static AdminPrincipal domain(HttpServletRequest request, Long department, long application) {
        application(request, application);
        return department(request, department);
    }

    public static AdminPrincipal allDepartments(HttpServletRequest request) {
        AdminPrincipal principal = of(request);
        if (!principal.allDepartments()) {
            throw ApiException.forbidden("该操作只对可管理全部部门的管理员开放");
        }
        return principal;
    }
}
