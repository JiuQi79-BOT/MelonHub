package com.melonhub.rbac.api.domainrbac.vo;

/**
 * 隔离域总览指标响应 VO。
 */
public record DomainOverviewVO(
        long applications,
        long enabledApplications,
        long departments,
        long users,
        long enabledUsers,
        long departmentMembers,
        long roles,
        long permissions,
        long members,
        long activeKeys,
        long activeContexts,
        long auditLast24h
) {}
