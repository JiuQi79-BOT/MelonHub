package com.melonhub.rbac.api.governance.vo;

import java.util.List;

/**
 * 权限清单中的角色项 VO。
 */
public record ManifestRoleVO(String code, String name, String description, boolean enabled, List<String> permissions) {}
