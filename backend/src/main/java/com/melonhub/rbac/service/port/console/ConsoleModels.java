package com.melonhub.rbac.service.port.console;

import com.melonhub.rbac.service.port.audit.AuditModels.AuditSealStateResult;
import java.util.List;

/**
 * 平台健康探针、控制台全局检索与系统状态看板的结果模型。
 */
public final class ConsoleModels {
    private ConsoleModels() {}

    public record HealthResult(String status, String database, String cache, String model) {}

    public record SearchUserResult(long id, String name, String externalId, boolean enabled) {}

    public record SearchDepartmentResult(long id, String name, String code) {}

    public record SearchApplicationResult(long id, String name, String code, boolean enabled) {}

    public record SearchRoleResult(
            long id,
            String name,
            String code,
            long departmentId,
            String departmentName,
            long applicationId,
            String applicationName
    ) {}

    public record SearchResultsResult(
            List<SearchUserResult> users,
            List<SearchDepartmentResult> departments,
            List<SearchApplicationResult> applications,
            List<SearchRoleResult> roles
    ) {}

    public record ConsoleCountsResult(
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

    public record SystemStatusResult(
            String version,
            long uptimeSeconds,
            String javaVersion,
            String cache,
            String database,
            long databaseLatencyMs,
            ConsoleCountsResult counts,
            Long pendingRequests,
            Long openReviews,
            Long webhookBacklog,
            Long expiringIn7Days,
            AuditSealStateResult audit
    ) {}
}
