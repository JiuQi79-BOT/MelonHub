package com.melonhub.rbac.api.governance.vo;

import java.util.List;

/**
 * 业务系统员工隔离域权限自查响应 VO。
 */
public record SelfAccessVO(
        long userId,
        long departmentId,
        long applicationId,
        List<SelfAccessRoleVO> roles,
        List<String> permissions
) {}
