package com.melonhub.rbac.api.governance.vo;

/**
 * 权限清单中的权限项 VO。
 */
public record ManifestPermissionVO(String code, String name, String resource, String action) {}
