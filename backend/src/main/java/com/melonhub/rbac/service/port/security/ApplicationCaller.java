package com.melonhub.rbac.service.port.security;

/**
 * 通过 API Key 认证的业务系统调用方主体。
 */
public record ApplicationCaller(long applicationId, long keyId) {
    public static final String ATTRIBUTE = "melonhub.application";

    public String actor() {
        return "app:" + applicationId;
    }
}
