package com.melonhub.rbac.infrastructure.security;

import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.security.ApplicationCaller;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 从 HTTP 请求属性解析已认证的业务软件调用方。
 */
public final class ApplicationAccess {
    private ApplicationAccess() {}

    public static ApplicationCaller of(HttpServletRequest request) {
        if (!(request.getAttribute(ApplicationCaller.ATTRIBUTE) instanceof ApplicationCaller caller)) {
            throw ApiException.unauthorized();
        }
        return caller;
    }
}
