package com.melonhub.rbac.infrastructure.security;

import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.security.AdminFunction;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 控制台功能权限拦截器：所有 {@code /api/admin/**} 接口必须声明 {@link RequiresFunction}，默认拒绝。
 */
@Component
public class AdminFunctionGuard implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresFunction required = method.getMethodAnnotation(RequiresFunction.class);
        if (required == null || required.value().length == 0) {
            throw ApiException.forbidden("接口未配置管理功能权限");
        }
        AdminPrincipal principal = AdminAccess.of(request);
        if (Arrays.stream(required.value()).noneMatch(principal::canUse)) {
            if (Arrays.stream(required.value()).anyMatch(principal::has)) {
                if (Arrays.stream(required.value()).filter(principal::has).allMatch(AdminFunction::platformAdminOnly)) {
                    throw ApiException.forbidden("该功能仅对超级管理员和平台管理员开放");
                }
                throw ApiException.forbidden("该功能只对可管理全部部门的管理员开放");
            }
            throw ApiException.forbidden("当前账号的角色未授权执行此操作");
        }
        return true;
    }
}
