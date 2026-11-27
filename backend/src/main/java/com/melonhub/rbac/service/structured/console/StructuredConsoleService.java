package com.melonhub.rbac.service.structured.console;

import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessRequestRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessReviewRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditSealRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionContextRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.WebhookDeliveryRepository;
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
import com.melonhub.rbac.service.structured.audit.StructuredAuditSealService;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Structured 控制台健康检查、全局搜索与系统状态看板服务。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "structured", matchIfMissing = true)
public class StructuredConsoleService implements ConsoleUseCase {
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final ApplicationRepository applications;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final UserRoleRepository userRoles;
    private final PermissionContextRepository contexts;
    private final AccessRequestRepository accessRequests;
    private final AccessReviewRepository accessReviews;
    private final WebhookDeliveryRepository webhookDeliveries;
    private final AuditEventRepository auditEvents;
    private final AuditSealRepository auditSeals;
    private final PermissionCache cache;

    public StructuredConsoleService(UserRepository users, DepartmentRepository departments,
                                    ApplicationRepository applications, RoleRepository roles,
                                    PermissionRepository permissions, UserRoleRepository userRoles,
                                    PermissionContextRepository contexts, AccessRequestRepository accessRequests,
                                    AccessReviewRepository accessReviews, WebhookDeliveryRepository webhookDeliveries,
                                    AuditEventRepository auditEvents, AuditSealRepository auditSeals,
                                    PermissionCache cache) {
        this.users = users;
        this.departments = departments;
        this.applications = applications;
        this.roles = roles;
        this.permissions = permissions;
        this.userRoles = userRoles;
        this.contexts = contexts;
        this.accessRequests = accessRequests;
        this.accessReviews = accessReviews;
        this.webhookDeliveries = webhookDeliveries;
        this.auditEvents = auditEvents;
        this.auditSeals = auditSeals;
        this.cache = cache;
    }

    @Override
    public HealthResult health() {
        boolean database;
        try {
            database = auditSeals.isDatabaseHealthy();
        } catch (RuntimeException e) {
            database = false;
        }
        String status = database ? "UP" : "DOWN";
        return new HealthResult(status, status, cache.health(), "RBAC1");
    }

    @Override
    public SearchResultsResult search(String keyword, Visibility visible) {
        String q = keyword == null ? "" : keyword.strip();
        if (q.isEmpty() || q.length() > 64) {
            return new SearchResultsResult(List.of(), List.of(), List.of(), List.of());
        }
        return new SearchResultsResult(
                users.searchVisible(q, visible.departments()).stream()
                        .map(u -> new SearchUserResult(u.id(), u.name(), u.externalId(), u.enabled())).toList(),
                departments.searchVisible(q, visible.departments()).stream()
                        .map(d -> new SearchDepartmentResult(d.id(), d.name(), d.code())).toList(),
                applications.searchVisible(q, visible.applications()).stream()
                        .map(a -> new SearchApplicationResult(a.id(), a.name(), a.code(), a.enabled())).toList(),
                roles.searchVisible(q, visible).stream()
                        .map(r -> new SearchRoleResult(r.id(), r.name(), r.code(), r.departmentId(), r.departmentName(),
                                r.applicationId(), r.applicationName())).toList());
    }

    @Override
    public SystemStatusResult status(Visibility visible) {
        String version = getClass().getPackage().getImplementationVersion() == null
                ? "dev" : getClass().getPackage().getImplementationVersion();
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        String javaVersion = System.getProperty("java.version");
        String cacheHealth = cache.health();
        long started = System.nanoTime();
        boolean database;
        try {
            database = auditSeals.isDatabaseHealthy();
        } catch (RuntimeException e) {
            database = false;
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        if (!database) {
            return new SystemStatusResult(version, uptimeSeconds, javaVersion, cacheHealth, "DOWN", latencyMs,
                    null, null, null, null, null, null);
        }
        try {
            ConsoleCountsResult counts = new ConsoleCountsResult(
                    users.count(), users.countByEnabledTrue(), departments.count(),
                    applications.count(), applications.countByEnabledTrue(),
                    roles.count(), permissions.count(), userRoles.count(), contexts.countActive());
            return new SystemStatusResult(
                    version, uptimeSeconds, javaVersion, cacheHealth, "UP", latencyMs,
                    counts,
                    accessRequests.countPending(visible),
                    accessReviews.countOpen(visible),
                    webhookDeliveries.countPendingBacklog(),
                    userRoles.countExpiringBy(Instant.now().plus(7, ChronoUnit.DAYS)),
                    StructuredAuditSealService.loadSealState(auditSeals, auditEvents));
        } catch (DataAccessException e) {
            return new SystemStatusResult(version, uptimeSeconds, javaVersion, cacheHealth, "DOWN", latencyMs,
                    null, null, null, null, null, null);
        }
    }
}
