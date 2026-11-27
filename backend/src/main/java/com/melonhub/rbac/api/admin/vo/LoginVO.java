package com.melonhub.rbac.api.admin.vo;

import java.util.List;

/**
 * 控制台管理员登录成功响应 VO。
 */
public record LoginVO(
        String token,
        int expiresIn,
        int idleTimeout,
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
