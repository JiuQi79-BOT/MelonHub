package com.melonhub.rbac.service.ddd.application.audit;

import com.melonhub.rbac.service.ddd.application.shared.DddBoundary;
import com.melonhub.rbac.service.ddd.domain.repository.PageSlice;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CryptoGateway;
import com.melonhub.rbac.service.ddd.domain.repository.RbacRepositoryPort;
import com.melonhub.rbac.service.port.audit.AuditModels.AuditEventResult;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.security.Visibility;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DDD 审计日志应用服务：经由领域仓储端口记录审计事件并执行分页检索。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class DddAuditService implements AuditUseCase {
    private final RbacRepositoryPort repository;
    private final CryptoGateway crypto;

    public DddAuditService(RbacRepositoryPort repository, CryptoGateway crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    @Override
    public void audit(String actor, String action, String target, Long department, Long app, String detail) {
        repository.recordAudit(actor, action, target, department, app, detail, crypto.currentClientAddress());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AuditEventResult> events(Long dept, Long app, Visibility visible, String actor, String action,
                                               String keyword, LocalDate from, LocalDate to, int page, int size) {
        Instant start = from == null ? null : from.atStartOfDay(PageResult.ZONE).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(PageResult.ZONE).toInstant();
        RbacRepositoryPort.AuditQuery query = new RbacRepositoryPort.AuditQuery(
                dept, app, DddBoundary.toDomainVisibility(visible), actor, action, keyword, start, end);
        PageSlice<RbacRepositoryPort.AuditEventRecord> slice = repository.auditEvents(query, page, size);
        List<AuditEventResult> items = slice.items().stream().map(e -> new AuditEventResult(
                e.id(), e.actor(), e.action(), e.target(), e.detail(), e.sourceIp(), e.createdAt(),
                e.departmentId(), e.departmentName(), e.applicationId(), e.applicationName())).toList();
        return PageResult.of(items, slice.total(), page, size);
    }
}
