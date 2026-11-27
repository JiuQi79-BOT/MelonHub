package com.melonhub.rbac.service.ddd.application.audit;

import com.melonhub.rbac.service.ddd.domain.model.AuditHashChainAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.AuditChainRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.TransactionRunner;
import com.melonhub.rbac.service.port.audit.AuditModels.AuditSealStateResult;
import com.melonhub.rbac.service.port.audit.AuditModels.AuditVerifyResult;
import com.melonhub.rbac.service.port.audit.AuditSealUseCase;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * DDD 审计哈希链封存应用服务：将哈希计算、前缀上界和断链/篡改判定委托给 {@link AuditHashChainAggregate}。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class DddAuditSealService implements AuditSealUseCase {
    private static final Logger log = LoggerFactory.getLogger(DddAuditSealService.class);
    private static final int BATCH = 500;
    private static final int MAX_BATCHES = 40;

    private final AuditChainRepositoryPort repository;
    private final TransactionRunner transactions;
    private final boolean scheduled;

    public DddAuditSealService(AuditChainRepositoryPort repository, TransactionRunner transactions,
                               @Value("${melonhub.maintenance.enabled:true}") boolean scheduled) {
        this.repository = repository;
        this.transactions = transactions;
        this.scheduled = scheduled;
    }

    @Override
    @Scheduled(initialDelay = 15_000, fixedDelay = 10_000)
    public void scheduledSeal() {
        if (scheduled) {
            sealNow();
        }
    }

    @Override
    public int sealNow() {
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES; i++) {
                int sealed = transactions.inTransaction(this::sealBatch);
                total += sealed;
                if (sealed < BATCH) {
                    break;
                }
            }
        } catch (DataAccessException e) {
            log.warn("Audit chain sealing skipped: {}", e.getClass().getSimpleName());
        }
        return total;
    }

    private int sealBatch() {
        AuditHashChainAggregate.SealMetadata seal = repository.lockSeal();
        AuditHashChainAggregate chain = new AuditHashChainAggregate(seal);
        List<AuditHashChainAggregate.AuditEventData> rows = repository.findUnsealed(BATCH);
        String previous = seal.lastHash();
        long seq = seal.lastSeq();
        long lastId = seal.lastId();
        int sealed = 0;
        for (AuditHashChainAggregate.AuditEventData row : rows) {
            String hash = chain.computeHash(previous, seq + 1, row);
            if (repository.sealEvent(row.id(), seq + 1, previous, hash) == 0) {
                continue;
            }
            seq++;
            previous = hash;
            lastId = row.id();
            sealed++;
        }
        if (sealed > 0) {
            repository.updateSealHead(lastId, seq, previous, sealed);
        }
        return rows.size();
    }

    @Override
    public int purge(int days) {
        return transactions.inTransaction(() -> {
            AuditHashChainAggregate.SealMetadata seal = repository.lockSeal();
            AuditHashChainAggregate chain = new AuditHashChainAggregate(seal);
            long below = chain.purgeUpperBoundSeq(repository.findFirstSeqSinceDays(days).orElse(null));
            Optional<AuditHashChainAggregate.ChainLink> end = repository.findPrefixEnd(below, PURGE_BATCH);
            if (end.isEmpty()) {
                return 0;
            }
            int removed = repository.deleteEventsThrough(end.get().seq());
            repository.updateAnchor(end.get());
            return removed;
        });
    }

    @Override
    public AuditVerifyResult verify(Long fromSeq, int limit) {
        sealNow();
        return transactions.inReadOnlyTransaction(() -> {
            long start = fromSeq == null ? 0 : fromSeq;
            AuditHashChainAggregate.SealMetadata seal = repository.readSeal();
            AuditHashChainAggregate chain = new AuditHashChainAggregate(seal);
            List<AuditHashChainAggregate.AuditEventData> rows = repository.findSealedSegment(start, seal.lastSeq(), limit);
            AuditHashChainAggregate.ChainLink before = start > 0 ? repository.findLinkBefore(start).orElse(null) : null;
            AuditHashChainAggregate.VerificationResult r = chain.verifySegment(rows, before, limit);
            return new AuditVerifyResult(
                    r.ok(), r.problem(), r.checked(), r.firstSeq(), r.lastSeq(), r.firstId(), r.lastId(),
                    r.brokenAt(), r.brokenSeq(), r.complete(), r.nextSeq(), toSealStateResult(repository.sealState()));
        });
    }

    public static AuditSealStateResult toSealStateResult(AuditHashChainAggregate.SealState s) {
        if (s == null) {
            return null;
        }
        return new AuditSealStateResult(s.lastId(), s.lastSeq(), s.lastHash(), s.sealedCount(), s.anchorSeq(),
                s.updatedAt(), s.unsealed(), s.total());
    }
}
