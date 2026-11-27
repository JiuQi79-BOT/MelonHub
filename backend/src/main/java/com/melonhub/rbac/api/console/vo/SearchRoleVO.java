package com.melonhub.rbac.api.console.vo;

/**
 * 全局检索角色结果项 VO。
 */
public record SearchRoleVO(
        long id,
        String name,
        String code,
        long departmentId,
        String departmentName,
        long applicationId,
        String applicationName
) {}
