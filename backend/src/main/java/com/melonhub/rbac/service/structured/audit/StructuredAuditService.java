package com.melonhub.rbac.service.structured.audit;

import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepositoryCustom;
import com.melonhub.rbac.infrastructure.security.ClientAddress;
import com.melonhub.rbac.service.port.audit.AuditModels.AuditEventResult;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.security.Visibility;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structured 审计日志服务：记录审计事件并按可见范围与条件分页检索。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "structured", matchIfMissing = true)
public class StructuredAuditService implements AuditUseCase {
    private final AuditEventRepository auditEvents;

    public StructuredAuditService(AuditEventRepository auditEvents) {
        this.auditEvents = auditEvents;
    }

    @Override
    public void audit(String actor, String action, String target, Long department, Long app, String detail) {
        auditEvents.recordAudit(actor, action, target, department, app, detail, ClientAddress.current());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AuditEventResult> events(Long dept, Long app, Visibility visible, String actor, String action,
                                               String keyword, LocalDate from, LocalDate to, int page, int size) {
        Instant start = from == null ? null : from.atStartOfDay(PageResult.ZONE).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(PageResult.ZONE).toInstant();
        AuditEventRepositoryCustom.AuditFilter filter =
                new AuditEventRepositoryCustom.AuditFilter(dept, app, visible, actor, action, keyword, start, end);
        Page<AuditEventRepositoryCustom.AuditEventRow> p = auditEvents.findAuditPage(filter, page, size);
        List<AuditEventResult> items = p.getContent().stream()
                .map(e -> new AuditEventResult(
                        e.id(), e.actor(), e.action(), e.target(), e.detail(), e.sourceIp(), e.createdAt(),
                        e.departmentId(), e.departmentName(), e.applicationId(), e.applicationName()))
                .toList();
        return PageResult.of(items, p.getTotalElements(), page, size);
    }
}
