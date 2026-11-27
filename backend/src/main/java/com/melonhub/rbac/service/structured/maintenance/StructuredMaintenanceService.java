package com.melonhub.rbac.service.structured.maintenance;

import com.melonhub.rbac.infrastructure.persistence.repository.PermissionContextRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import com.melonhub.rbac.service.port.audit.AuditSealUseCase;
import com.melonhub.rbac.service.port.governance.GovernanceUseCase;
import com.melonhub.rbac.service.port.maintenance.MaintenanceUseCase;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Structured 后台周期性维护任务服务：分批清理过期分配、会话、上下文、投递与历史审计。
 */
@Service
@ConditionalOnExpression("'${melonhub.maintenance.enabled:true}'.equalsIgnoreCase('true') and '${melonhub.backend-strategy:structured}'.equalsIgnoreCase('structured')")
public class StructuredMaintenanceService implements MaintenanceUseCase {
    private static final Logger log = LoggerFactory.getLogger(StructuredMaintenanceService.class);
    private static final int BATCH = 5000;
    private static final int EXPIRY_BATCH = 500;
    private static final int MAX_BATCHES = 200;

    private final PermissionContextRepository contexts;
    private final AuthUseCase auth;
    private final GovernanceUseCase governance;
    private final WebhookDeliveryRepository webhookDeliveries;
    private final AuditSealUseCase audit;
    private final int auditDays;
    private final int webhookDays;

    public StructuredMaintenanceService(PermissionContextRepository contexts, AuthUseCase auth,
                                        GovernanceUseCase governance, WebhookDeliveryRepository webhookDeliveries,
                                        AuditSealUseCase audit,
                                        @Value("${melonhub.retention.audit-days:180}") int auditDays,
                                        @Value("${melonhub.retention.webhook-days:30}") int webhookDays) {
        this.contexts = contexts;
        this.auth = auth;
        this.governance = governance;
        this.webhookDeliveries = webhookDeliveries;
        this.audit = audit;
        this.auditDays = auditDays;
        this.webhookDays = webhookDays;
    }

    @Override
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void expireAssignments() {
        try {
            int expired = drain(EXPIRY_BATCH, () -> governance.expireAssignments(EXPIRY_BATCH));
            if (expired > 0) {
                log.info("Expired {} role assignments", expired);
            }
        } catch (DataAccessException e) {
            log.warn("Assignment expiry skipped: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void purgeExpired() {
        try {
            int removedContexts = drain(BATCH, () -> contexts.purgeExpired(BATCH));
            int removedSessions = auth.purgeSessions();
            int removedDeliveries = webhookDays > 0 ? drain(BATCH, () -> webhookDeliveries.purgeFinished(webhookDays, BATCH)) : 0;
            if (removedContexts + removedSessions + removedDeliveries > 0) {
                log.info("Purged {} contexts, {} sessions, {} webhook deliveries",
                        removedContexts, removedSessions, removedDeliveries);
            }
        } catch (DataAccessException e) {
            log.warn("Expired data cleanup skipped: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void purgeAudit() {
        if (auditDays <= 0) {
            return;
        }
        try {
            int removed = drain(AuditSealUseCase.PURGE_BATCH, () -> audit.purge(auditDays));
            if (removed > 0) {
                log.info("Purged {} audit events older than {} days", removed, auditDays);
            }
        } catch (DataAccessException e) {
            log.warn("Audit retention cleanup skipped: {}", e.getClass().getSimpleName());
        }
    }

    private static int drain(int size, IntSupplier batch) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int removed = batch.getAsInt();
            total += removed;
            if (removed < size) {
                break;
            }
        }
        return total;
    }
}
