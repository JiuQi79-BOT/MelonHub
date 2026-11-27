package com.melonhub.rbac.service.ddd.domain.model;

/**
 * 系统状态看板领域报告模型：守护数据库不可达或半途异常时输出固定结构且丢弃半截数据的降级契约。
 */
public final class SystemStatusReport {
    public record ConsoleCounts(
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

    public record Snapshot(
            String version,
            long uptimeSeconds,
            String javaVersion,
            String cache,
            String database,
            long databaseLatencyMs,
            ConsoleCounts counts,
            Long pendingRequests,
            Long openReviews,
            Long webhookBacklog,
            Long expiringIn7Days,
            AuditHashChainAggregate.SealState audit
    ) {}

    private SystemStatusReport() {}

    public static Snapshot degraded(String version, long uptimeSeconds, String javaVersion,
                                    String cacheHealth, long databaseLatencyMs) {
        return new Snapshot(version, uptimeSeconds, javaVersion, cacheHealth, "DOWN", databaseLatencyMs,
                null, null, null, null, null, null);
    }

    public static Snapshot healthy(String version, long uptimeSeconds, String javaVersion,
                                   String cacheHealth, long databaseLatencyMs,
                                   ConsoleCounts counts, long pendingRequests, long openReviews,
                                   long webhookBacklog, long expiringIn7Days,
                                   AuditHashChainAggregate.SealState auditState) {
        return new Snapshot(version, uptimeSeconds, javaVersion, cacheHealth, "UP", databaseLatencyMs,
                counts, pendingRequests, openReviews, webhookBacklog, expiringIn7Days, auditState);
    }
}
