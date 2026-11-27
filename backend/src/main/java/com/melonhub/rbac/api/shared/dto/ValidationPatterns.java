package com.melonhub.rbac.api.shared.dto;

/**
 * HTTP 入站 DTO 共享正则与格式常量。
 */
public final class ValidationPatterns {
    private ValidationPatterns() {}

    public static final String CODE = "[a-zA-Z0-9_.-]{1,64}";
    public static final String DOMAIN_CODE = "[a-zA-Z0-9_.:-]{1,100}";
    public static final String USERNAME = "[a-zA-Z0-9_@-][a-zA-Z0-9_.@-]{2,99}";
    public static final String ADMIN_ROLE_CODE = "[A-Z][A-Z0-9_]{1,31}";
    public static final String ADMIN_KIND = "MANAGER|DEPARTMENT";
}
