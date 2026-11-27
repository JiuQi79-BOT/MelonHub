package com.melonhub.rbac.api.admin.vo;

/**
 * TOTP 两步验证待绑定密钥与二维码 URI 响应 VO。
 */
public record MfaSetupVO(String secret, String uri) {}
