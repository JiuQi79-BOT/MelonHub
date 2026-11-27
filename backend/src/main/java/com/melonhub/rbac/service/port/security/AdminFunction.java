package com.melonhub.rbac.service.port.security;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 控制台 19 项平台管理功能枚举，与数据库 {@code admin_functions} 表一一对应。
 */
public enum AdminFunction {
    ORG_VIEW("org.view"),
    DEPARTMENT_MANAGE("department.manage", true),
    USER_MANAGE("user.manage"),
    USER_STATUS("user.status"),
    USER_DELETE("user.delete"),
    USER_CREDENTIALS("user.credentials"),
    APPLICATION_MANAGE("application.manage", true),
    APPLICATION_CREDENTIALS("application.credentials", true),
    DOMAIN_VIEW("domain.view"),
    DOMAIN_CONFIGURE("domain.configure"),
    DOMAIN_ASSIGN("domain.assign"),
    DOMAIN_TRANSFER("domain.transfer"),
    GOVERNANCE_VIEW("governance.view"),
    REQUEST_APPROVE("request.approve"),
    REVIEW_MANAGE("review.manage"),
    AUDIT_VIEW("audit.view"),
    SYSTEM_VIEW("system.view"),
    ACCOUNT_MANAGE("account.manage"),
    ROLE_MANAGE("role.manage", true);

    private static final Map<String, AdminFunction> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toMap(AdminFunction::code, Function.identity()));

    private final String code;
    private final boolean platformWide;

    AdminFunction(String code) {
        this(code, false);
    }

    AdminFunction(String code, boolean platformWide) {
        this.code = code;
        this.platformWide = platformWide;
    }

    public String code() {
        return code;
    }

    public boolean platformWide() {
        return platformWide;
    }

    public boolean platformAdminOnly() {
        return this == USER_MANAGE || this == USER_CREDENTIALS;
    }

    /** 按编码查找功能项；未知编码返回 {@code null}。 */
    public static AdminFunction of(String code) {
        return BY_CODE.get(code);
    }
}
