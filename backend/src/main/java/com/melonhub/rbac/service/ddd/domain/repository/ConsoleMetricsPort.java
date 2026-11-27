package com.melonhub.rbac.service.ddd.domain.repository;

import com.melonhub.rbac.service.ddd.domain.model.AuditHashChainAggregate;
import com.melonhub.rbac.service.ddd.domain.model.SystemStatusReport;
import com.melonhub.rbac.service.ddd.domain.value.DomainVisibility;
import java.time.Instant;
import java.util.List;

/**
 * 控制台全局检索与系统状态看板领域查询端口。
 */
public interface ConsoleMetricsPort {
    record SearchUserRecord(long id, String name, String externalId, boolean enabled) {}

    record SearchDepartmentRecord(long id, String name, String code) {}

    record SearchApplicationRecord(long id, String name, String code, boolean enabled) {}

    record SearchRoleRecord(long id, String name, String code, long departmentId, String departmentName,
                            long applicationId, String applicationName) {}

    List<SearchUserRecord> searchUsers(String keyword, DomainVisibility visibility);

    List<SearchDepartmentRecord> searchDepartments(String keyword, DomainVisibility visibility);

    List<SearchApplicationRecord> searchApplications(String keyword, DomainVisibility visibility);

    List<SearchRoleRecord> searchRoles(String keyword, DomainVisibility visibility);

    String cacheHealth();

    boolean databaseHealthy();

    SystemStatusReport.ConsoleCounts consoleCounts();

    long pendingRequestCount(DomainVisibility visibility);

    long openReviewCount(DomainVisibility visibility);

    long webhookBacklog();

    long expiringAssignmentCount(Instant deadline);

    AuditHashChainAggregate.SealState auditSealState();
}
