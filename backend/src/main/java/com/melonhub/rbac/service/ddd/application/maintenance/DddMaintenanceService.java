package com.melonhub.rbac.service.ddd.application.maintenance;

import com.melonhub.rbac.service.ddd.domain.repository.PermissionContextRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.WebhookRepositoryPort;
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
 * DDD 周期性后台维护应用服务：到期角色回收、过期凭据与投递清理、审计前缀保留期裁剪。
 */
@Service
@ConditionalOnExpression("'${melonhub.maintenance.enabled:true}'.equalsIgnoreCase('true') and '${melonhub.backend-strategy:structured}'.equalsIgnoreCase('ddd')")
public class DddMaintenanceService implements MaintenanceUseCase {
    private static final Logger log = LoggerFactory.getLogger(DddMaintenanceService.class);
    private static final int BATCH = 5000;
    private static final int EXPIRE_BATCH = 500;
    private static final int MAX_BATCHES = 200;

    private final PermissionContextRepositoryPort contexts;
    private final AuthUseCase auth;
    private final GovernanceUseCase governance;
    private final WebhookRepositoryPort webhooks;
    private final AuditSealUseCase audit;
    private final int auditDays;
    private final int webhookDays;

    public DddMaintenanceService(PermissionContextRepositoryPort contexts, AuthUseCase auth, GovernanceUseCase governance,
                                 WebhookRepositoryPort webhooks, AuditSealUseCase audit,
                                 @Value("${melonhub.retention.audit-days:180}") int auditDays,
                                 @Value("${melonhub.retention.webhook-days:30}") int webhookDays) {
        this.contexts = contexts;
        this.auth = auth;
        this.governance = governance;
        this.webhooks = webhooks;
        this.audit = audit;
        this.auditDays = auditDays;
        this.webhookDays = webhookDays;
    }

    @Override
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void expireAssignments() {
        try {
            int expired = drain(EXPIRE_BATCH, () -> governance.expireAssignments(EXPIRE_BATCH));
            if (expired > 0) {
                log.info("Expired {} role assignments", expired);
            }
        } catch (DataAccessException e) {
            log.warn("Role assignment expiry skipped: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void purgeExpired() {
        try {
            int droppedContexts = drain(BATCH, () -> contexts.purgeExpired(BATCH));
            int droppedSessions = auth.purgeSessions();
            int droppedDeliveries = webhookDays > 0 ? drain(BATCH, () -> webhooks.purgeDeliveries(webhookDays, BATCH)) : 0;
            if (droppedContexts + droppedSessions + droppedDeliveries > 0) {
                log.info("Purged {} contexts, {} sessions, {} webhook deliveries",
                        droppedContexts, droppedSessions, droppedDeliveries);
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

    private static int drain(int batch, IntSupplier step) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int changed = step.getAsInt();
            total += changed;
            if (changed < batch) {
                break;
            }
        }
        return total;
    }
}
