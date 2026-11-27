package com.melonhub.rbac.api.identity.vo;

/**
 * 统一身份单项权限校验响应 VO。
 */
public record IdentityPermissionCheckVO(String permission, boolean allowed) {}
