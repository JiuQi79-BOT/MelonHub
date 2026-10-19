package com.melonhub.rbac.security;

import com.melonhub.rbac.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;

public final class AdminAccess {
    public static final String ATTRIBUTE = "melonhub.admin";
    private AdminAccess() {}

    public static AdminPrincipal of(HttpServletRequest request) {
        if (!(request.getAttribute(ATTRIBUTE) instanceof AdminPrincipal principal)) throw ApiException.unauthorized();
        return principal;
    }
    public static AdminPrincipal platformWrite(HttpServletRequest request) {
        var principal = of(request);
        if (!principal.platformWriter()) throw ApiException.forbidden("当前管理员角色无权执行此操作");
        return principal;
    }
    public static AdminPrincipal superAdmin(HttpServletRequest request) {
        var principal = of(request);
        if (!principal.superAdmin()) throw ApiException.forbidden("仅超级管理员可以管理平台账号");
        return principal;
    }
    public static AdminPrincipal readApplication(HttpServletRequest request, long application) {
        var principal = of(request);
        if (!principal.canRead(application)) throw ApiException.forbidden("当前账号未被授权管理该软件");
        return principal;
    }
    public static AdminPrincipal writeApplication(HttpServletRequest request, long application) {
        var principal = of(request);
        if (!principal.canWrite(application)) throw ApiException.forbidden("当前账号未被授权修改该软件的权限配置");
        return principal;
    }
}
