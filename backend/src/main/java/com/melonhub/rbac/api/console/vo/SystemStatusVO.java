package com.melonhub.rbac.api.console.vo;

import com.melonhub.rbac.api.audit.vo.AuditSealStateVO;

/**
 * 系统运行状态与降级容错看板响应 VO。
 */
public record SystemStatusVO(
        String version,
        long uptimeSeconds,
        String javaVersion,
        String cache,
        String database,
        long databaseLatencyMs,
        ConsoleCountsVO counts,
        Long pendingRequests,
        Long openReviews,
        Long webhookBacklog,
        Long expiringIn7Days,
        AuditSealStateVO audit
) {}
