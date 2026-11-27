package com.melonhub.rbac.api.governance.vo;

import java.util.List;

/**
 * 导出权限清单响应 VO。
 */
public record PermissionManifestVO(
        int version,
        ManifestApplicationVO application,
        String exportedAt,
        List<ManifestDepartmentVO> departments
) {}
