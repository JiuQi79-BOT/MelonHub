package com.melonhub.rbac.api.admin.vo;

import java.util.List;

/**
 * 当前登录管理员身份响应 VO。
 */
public record PrincipalVO(
        long id,
        String username,
        String displayName,
        String role,
        String roleName,
        List<String> functions,
        boolean root,
        String kind,
        boolean allDepartments,
        List<Long> departmentIds,
        boolean allApplications,
        List<Long> applicationIds,
        boolean mfaEnabled
) {}
