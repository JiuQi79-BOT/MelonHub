package com.melonhub.rbac.api.organization.vo;

import java.util.List;

/**
 * 用户权限全览中单个隔离域授权摘要 VO。
 */
public record AccessDomainVO(
        long departmentId,
        String departmentName,
        long applicationId,
        String applicationName,
        boolean applicationEnabled,
        List<AccessDomainRoleVO> roles,
        List<String> permissions
) {}
