package com.melonhub.rbac.api.governance.vo;

import java.util.List;

/**
 * 权限清单中的部门配置节 VO。
 */
public record ManifestDepartmentVO(
        String code,
        String name,
        List<ManifestPermissionVO> permissions,
        List<ManifestRoleVO> roles
) {}
