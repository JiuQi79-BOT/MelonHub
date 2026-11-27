package com.melonhub.rbac.api.governance.vo;

/**
 * 导入权限清单统计响应 VO。
 */
public record ManifestImportResultVO(int permissionsCreated, int permissionsUpdated, int rolesCreated, int rolesUpdated) {}
