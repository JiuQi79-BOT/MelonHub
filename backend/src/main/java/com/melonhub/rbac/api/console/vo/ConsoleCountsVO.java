package com.melonhub.rbac.api.console.vo;

/**
 * 系统状态看板实体计数汇总 VO。
 */
public record ConsoleCountsVO(
        long users,
        long enabledUsers,
        long departments,
        long applications,
        long enabledApplications,
        long roles,
        long permissions,
        long assignments,
        long activeContexts
) {}
