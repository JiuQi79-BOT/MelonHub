package com.melonhub.rbac.api.identity.vo;

/**
 * 统一身份单个 API 操作权限校验响应 VO。
 */
public record IdentityApiPermissionCheckVO(String operationId, boolean allowed) {}
