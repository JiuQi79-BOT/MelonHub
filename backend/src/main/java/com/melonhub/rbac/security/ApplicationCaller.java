package com.melonhub.rbac.security;

import com.melonhub.rbac.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;

/** Business application authenticated by one of its API keys. */
public record ApplicationCaller(long applicationId, long keyId) {
    public static final String ATTRIBUTE = "melonhub.application";

    public static ApplicationCaller of(HttpServletRequest request) {
        if (!(request.getAttribute(ATTRIBUTE) instanceof ApplicationCaller caller)) throw ApiException.unauthorized();
        return caller;
    }
    public String actor() { return "app:" + applicationId; }
}
