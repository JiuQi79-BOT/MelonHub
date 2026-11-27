package com.melonhub.rbac.api.governance.vo;

import java.util.List;

/**
 * 权限反查中单个持有用户 VO。
 */
public record PermissionHolderVO(
        long userId,
        String userName,
        String externalId,
        long departmentId,
        String departmentName,
        List<PermissionHolderRoleVO> roles
) {}
