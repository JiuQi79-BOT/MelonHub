package com.melonhub.rbac.service.ddd.application.console;

import com.melonhub.rbac.service.ddd.application.audit.DddAuditSealService;
import com.melonhub.rbac.service.ddd.application.shared.DddBoundary;
import com.melonhub.rbac.service.ddd.domain.model.SystemStatusReport;
import com.melonhub.rbac.service.ddd.domain.repository.ConsoleMetricsPort;
import com.melonhub.rbac.service.ddd.domain.value.DomainVisibility;
import com.melonhub.rbac.service.port.console.ConsoleModels.ConsoleCountsResult;
import com.melonhub.rbac.service.port.console.ConsoleModels.HealthResult;
import com.melonhub.rbac.service.port.console.ConsoleModels.SearchApplicationResult;
import com.melonhub.rbac.service.port.console.ConsoleModels.SearchDepartmentResult;
import com.melonhub.rbac.service.port.console.ConsoleModels.SearchResultsResult;
import com.melonhub.rbac.service.port.console.ConsoleModels.SearchRoleResult;
import com.melonhub.rbac.service.port.console.ConsoleModels.SearchUserResult;
import com.melonhub.rbac.service.port.console.ConsoleModels.SystemStatusResult;
import com.melonhub.rbac.service.port.console.ConsoleUseCase;
import com.melonhub.rbac.service.port.security.Visibility;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * DDD 控制台应用服务：提供健康探针、全局检索与容错降级系统状态看板。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class DddConsoleService implements ConsoleUseCase {
    private final ConsoleMetricsPort metrics;

    public DddConsoleService(ConsoleMetricsPort metrics) {
        this.metrics = metrics;
    }

    @Override
    public HealthResult health() {
        boolean database;
        try {
            database = metrics.databaseHealthy();
        } catch (RuntimeException e) {
            database = false;
        }
        String status = database ? "UP" : "DOWN";
        return new HealthResult(status, status, metrics.cacheHealth(), "RBAC1");
    }

    @Override
    public SearchResultsResult search(String keyword, Visibility visible) {
        String query = keyword == null ? "" : keyword.strip();
        if (query.isEmpty() || query.length() > 64) {
            return new SearchResultsResult(List.of(), List.of(), List.of(), List.of());
        }
        DomainVisibility domainVisible = DddBoundary.toDomainVisibility(visible);
        return new SearchResultsResult(
                metrics.searchUsers(query, domainVisible).stream()
                        .map(u -> new SearchUserResult(u.id(), u.name(), u.externalId(), u.enabled())).toList(),
                metrics.searchDepartments(query, domainVisible).stream()
                        .map(d -> new SearchDepartmentResult(d.id(), d.name(), d.code())).toList(),
                metrics.searchApplications(query, domainVisible).stream()
                        .map(a -> new SearchApplicationResult(a.id(), a.name(), a.code(), a.enabled())).toList(),
                metrics.searchRoles(query, domainVisible).stream()
                        .map(r -> new SearchRoleResult(r.id(), r.name(), r.code(), r.departmentId(), r.departmentName(),
                                r.applicationId(), r.applicationName())).toList());
    }

    @Override
    public SystemStatusResult status(Visibility visible) {
        String version = getClass().getPackage().getImplementationVersion() == null
                ? "dev" : getClass().getPackage().getImplementationVersion();
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        String javaVersion = System.getProperty("java.version");
        String cacheHealth = metrics.cacheHealth();
        long started = System.nanoTime();
        boolean healthy;
        try {
            healthy = metrics.databaseHealthy();
        } catch (RuntimeException e) {
            healthy = false;
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        if (!healthy) {
            return toResult(SystemStatusReport.degraded(version, uptimeSeconds, javaVersion, cacheHealth, latencyMs));
        }
        DomainVisibility domainVisible = DddBoundary.toDomainVisibility(visible);
        try {
            return toResult(SystemStatusReport.healthy(
                    version, uptimeSeconds, javaVersion, cacheHealth, latencyMs,
                    metrics.consoleCounts(),
                    metrics.pendingRequestCount(domainVisible),
                    metrics.openReviewCount(domainVisible),
                    metrics.webhookBacklog(),
                    metrics.expiringAssignmentCount(Instant.now().plus(7, ChronoUnit.DAYS)),
                    metrics.auditSealState()));
        } catch (DataAccessException e) {
            return toResult(SystemStatusReport.degraded(version, uptimeSeconds, javaVersion, cacheHealth, latencyMs));
        }
    }

    private static SystemStatusResult toResult(SystemStatusReport.Snapshot s) {
        ConsoleCountsResult counts = s.counts() == null ? null : new ConsoleCountsResult(
                s.counts().users(), s.counts().enabledUsers(), s.counts().departments(),
                s.counts().applications(), s.counts().enabledApplications(), s.counts().roles(),
                s.counts().permissions(), s.counts().assignments(), s.counts().activeContexts());
        return new SystemStatusResult(
                s.version(), s.uptimeSeconds(), s.javaVersion(), s.cache(), s.database(), s.databaseLatencyMs(),
                counts, s.pendingRequests(), s.openReviews(), s.webhookBacklog(), s.expiringIn7Days(),
                DddAuditSealService.toSealStateResult(s.audit()));
    }
}
