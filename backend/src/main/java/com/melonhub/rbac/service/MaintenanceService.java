package com.melonhub.rbac.service;

import com.melonhub.rbac.dao.ContextDao;
import com.melonhub.rbac.dao.WebhookDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Removes expired rows in small batches so cleanup never holds long locks on hot tables. */
@Service
@ConditionalOnProperty(name = "melonhub.maintenance.enabled", havingValue = "true", matchIfMissing = true)
public class MaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);
    private static final int BATCH = 5000;
    private static final int EXPIRY_BATCH = 500;
    private static final int MAX_BATCHES = 200;

    private final ContextDao contexts;
    private final AuthService auth;
    private final GovernanceService governance;
    private final WebhookDao webhooks;
    private final AuditSealService audit;
    private final int auditDays;
    private final int webhookDays;

    public MaintenanceService(ContextDao contexts, AuthService auth, GovernanceService governance, WebhookDao webhooks, AuditSealService audit,
                              @Value("${melonhub.retention.audit-days:180}") int auditDays,
                              @Value("${melonhub.retention.webhook-days:30}") int webhookDays) {
        this.contexts = contexts; this.auth = auth; this.governance = governance; this.webhooks = webhooks; this.audit = audit;
        this.auditDays = auditDays; this.webhookDays = webhookDays;
    }

    /** Lapsed assignments already stop counting in every query; this job announces them and removes the rows. */
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void expireAssignments() {
        try {
            int expired = drain(EXPIRY_BATCH, () -> governance.expireAssignments(EXPIRY_BATCH));
            if (expired > 0) log.info("Expired {} role assignments", expired);
        } catch (DataAccessException e) {
            log.warn("Assignment expiry skipped: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void purgeExpired() {
        try {
            int removedContexts = drain(BATCH, () -> contexts.purgeExpired(BATCH));
            int removedSessions = auth.purgeSessions();
            int removedDeliveries = webhookDays > 0 ? drain(BATCH, () -> webhooks.purge(webhookDays, BATCH)) : 0;
            if (removedContexts + removedSessions + removedDeliveries > 0)
                log.info("Purged {} contexts, {} sessions, {} webhook deliveries", removedContexts, removedSessions, removedDeliveries);
        } catch (DataAccessException e) {
            log.warn("Expired data cleanup skipped: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void purgeAudit() {
        if (auditDays <= 0) return;
        try {
            int removed = drain(AuditSealService.PURGE_BATCH, () -> audit.purge(auditDays));
            if (removed > 0) log.info("Purged {} audit events older than {} days", removed, auditDays);
        } catch (DataAccessException e) {
            log.warn("Audit retention cleanup skipped: {}", e.getClass().getSimpleName());
        }
    }

    private static int drain(int size, java.util.function.IntSupplier batch) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int removed = batch.getAsInt();
            total += removed;
            if (removed < size) break;
        }
        return total;
    }
}
