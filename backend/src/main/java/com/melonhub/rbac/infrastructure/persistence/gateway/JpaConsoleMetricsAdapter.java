package com.melonhub.rbac.infrastructure.persistence.gateway;

import static com.melonhub.rbac.infrastructure.persistence.gateway.JpaRbacRepositoryAdapter.toVisibility;

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
import com.melonhub.rbac.service.ddd.domain.model.AuditHashChainAggregate;
import com.melonhub.rbac.service.ddd.domain.model.SystemStatusReport;
import com.melonhub.rbac.service.ddd.domain.repository.ConsoleMetricsPort;
import com.melonhub.rbac.service.ddd.domain.value.DomainVisibility;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** DDD 基础设施适配器：将 {@link ConsoleMetricsPort} 桥接到各 Spring Data JPA 仓储与缓存健康探针。 */
@Repository
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class JpaConsoleMetricsAdapter implements ConsoleMetricsPort {
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

    public JpaConsoleMetricsAdapter(UserRepository users, DepartmentRepository departments,
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
    public List<SearchUserRecord> searchUsers(String keyword, DomainVisibility visibility) {
        return users.searchVisible(keyword, visibility.departments()).stream()
                .map(u -> new SearchUserRecord(u.id(), u.name(), u.externalId(), u.enabled())).toList();
    }

    @Override
    public List<SearchDepartmentRecord> searchDepartments(String keyword, DomainVisibility visibility) {
        return departments.searchVisible(keyword, visibility.departments()).stream()
                .map(d -> new SearchDepartmentRecord(d.id(), d.name(), d.code())).toList();
    }

    @Override
    public List<SearchApplicationRecord> searchApplications(String keyword, DomainVisibility visibility) {
        return applications.searchVisible(keyword, visibility.applications()).stream()
                .map(a -> new SearchApplicationRecord(a.id(), a.name(), a.code(), a.enabled())).toList();
    }

    @Override
    public List<SearchRoleRecord> searchRoles(String keyword, DomainVisibility visibility) {
        return roles.searchVisible(keyword, toVisibility(visibility)).stream()
                .map(r -> new SearchRoleRecord(r.id(), r.name(), r.code(), r.departmentId(), r.departmentName(),
                        r.applicationId(), r.applicationName())).toList();
    }

    @Override public String cacheHealth() { return cache.health(); }
    @Override public boolean databaseHealthy() { return auditSeals.isDatabaseHealthy(); }

    @Override
    public SystemStatusReport.ConsoleCounts consoleCounts() {
        return new SystemStatusReport.ConsoleCounts(
                users.count(), users.countByEnabledTrue(), departments.count(),
                applications.count(), applications.countByEnabledTrue(),
                roles.count(), permissions.count(), userRoles.count(), contexts.countActive());
    }

    @Override public long pendingRequestCount(DomainVisibility visibility) { return accessRequests.countPending(toVisibility(visibility)); }
    @Override public long openReviewCount(DomainVisibility visibility) { return accessReviews.countOpen(toVisibility(visibility)); }
    @Override public long webhookBacklog() { return webhookDeliveries.countPendingBacklog(); }
    @Override public long expiringAssignmentCount(Instant deadline) { return userRoles.countExpiringBy(deadline); }
    @Override public AuditHashChainAggregate.SealState auditSealState() { return JpaAuditChainRepositoryAdapter.loadSealState(auditSeals, auditEvents); }
}
