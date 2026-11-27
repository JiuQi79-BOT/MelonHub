package com.melonhub.rbac.service.structured.audit;

import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditSealRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditSealRepositoryCustom;
import com.melonhub.rbac.service.port.audit.AuditModels.AuditSealStateResult;
import com.melonhub.rbac.service.port.audit.AuditModels.AuditVerifyResult;
import com.melonhub.rbac.service.port.audit.AuditSealUseCase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Structured 审计哈希链封存服务：定期对审计日志按序封链、执行保留期前缀裁剪与链路完整性校验。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "structured", matchIfMissing = true)
public class StructuredAuditSealService implements AuditSealUseCase {
    public static final String GENESIS = "0".repeat(64);

    public record ChainRow(long id, String actor, String action, String target, Long departmentId, Long applicationId,
                           String detail, String sourceIp, long ts, Long sealSeq, String prevHash, String chainHash) {}

    private static final Logger log = LoggerFactory.getLogger(StructuredAuditSealService.class);
    private static final int BATCH = 500;
    private static final int MAX_BATCHES = 40;

    private final AuditEventRepository events;
    private final AuditSealRepository seals;
    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshot;
    private final boolean scheduled;

    public StructuredAuditSealService(AuditEventRepository events, AuditSealRepository seals,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${melonhub.maintenance.enabled:true}") boolean scheduled) {
        this.events = events;
        this.seals = seals;
        this.transactions = new TransactionTemplate(transactionManager);
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
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
                Integer sealed = transactions.execute(status -> sealBatch());
                int count = sealed == null ? 0 : sealed;
                total += count;
                if (count < BATCH) {
                    break;
                }
            }
        } catch (DataAccessException e) {
            log.warn("Audit chain sealing skipped: {}", e.getClass().getSimpleName());
        }
        return total;
    }

    private int sealBatch() {
        AuditSealRepositoryCustom.SealSnapshot seal = seals.lockSeal();
        List<AuditEventRepository.ChainEvent> rows = events.findUnsealed(Limit.of(BATCH));
        String previous = seal.lastHash();
        long seq = seal.lastSeq();
        long lastId = seal.lastId();
        int sealed = 0;
        for (AuditEventRepository.ChainEvent item : rows) {
            ChainRow row = toChainRow(item);
            long id = row.id();
            String hash = chainHash(previous, seq + 1, row, seal.v2FromSeq());
            if (events.sealEvent(id, seq + 1, previous, hash) == 0) {
                continue;
            }
            seq++;
            previous = hash;
            lastId = id;
            sealed++;
        }
        if (sealed > 0) {
            seals.updateSeal(lastId, seq, previous, sealed);
        }
        return rows.size();
    }

    @Override
    public int purge(int days) {
        Integer result = transactions.execute(status -> {
            AuditSealRepositoryCustom.SealSnapshot seal = seals.lockSeal();
            long below = events.findFirstSeqWithinDays(days).map(seq -> Math.min(seq, seal.lastSeq())).orElse(seal.lastSeq());
            Optional<AuditEventRepository.Link> end = events.findPrefixEnd(below, PURGE_BATCH);
            if (end.isEmpty()) {
                return 0;
            }
            int removed = events.deleteThroughSeq(end.get().seq());
            seals.updateAnchor(end.get().seq(), end.get().hash());
            return removed;
        });
        return result == null ? 0 : result;
    }

    @Override
    public AuditVerifyResult verify(Long fromSeq, int limit) {
        sealNow();
        return snapshot.execute(status -> walk(fromSeq == null ? 0 : fromSeq, limit));
    }

    private AuditVerifyResult walk(long start, int limit) {
        AuditSealRepositoryCustom.SealSnapshot seal = seals.readSeal();
        List<AuditEventRepository.ChainEvent> rows = events.findSealedRange(start, seal.lastSeq(), Limit.of(limit));
        Optional<AuditEventRepository.Link> before = start > 0 ? events.findLinkBefore(start) : Optional.empty();
        String expected = before.map(AuditEventRepository.Link::hash).orElse(seal.anchorHash());
        long seen = before.map(AuditEventRepository.Link::seq).orElse(seal.anchorSeq());
        long checked = 0;
        long firstId = -1;
        long lastId = -1;
        long firstSeq = -1;
        long brokenAt = -1;
        long brokenSeq = -1;
        String problem = null;
        for (AuditEventRepository.ChainEvent item : rows) {
            ChainRow row = toChainRow(item);
            long id = row.id();
            long seq = row.sealSeq();
            String previous = row.prevHash();
            if (!constantTimeEquals(previous, expected)) {
                problem = "LINK_BROKEN";
            } else if (!constantTimeEquals(row.chainHash(), chainHash(previous, seq, row, seal.v2FromSeq()))) {
                problem = "MODIFIED";
            }
            if (problem != null) {
                brokenAt = id;
                brokenSeq = seq;
                break;
            }
            expected = row.chainHash();
            seen = seq;
            if (firstId < 0) {
                firstId = id;
                firstSeq = seq;
            }
            lastId = id;
            checked++;
        }
        boolean complete = problem == null && (rows.size() < limit || seen >= seal.lastSeq());
        if (complete && (seen != seal.lastSeq() || !constantTimeEquals(expected, seal.lastHash()))) {
            problem = "TAIL_MISSING";
        }
        return new AuditVerifyResult(
                problem == null,
                problem,
                checked,
                firstSeq < 0 ? null : firstSeq,
                checked == 0 ? null : seen,
                firstId < 0 ? null : firstId,
                lastId < 0 ? null : lastId,
                brokenAt < 0 ? null : brokenAt,
                brokenSeq < 0 ? null : brokenSeq,
                complete,
                problem == null && !complete ? seen + 1 : null,
                loadSealState(seals, events));
    }

    public static AuditSealStateResult loadSealState(AuditSealRepository seals, AuditEventRepository events) {
        AuditSealRepository.SealSummary s = seals.findSealSummary();
        return new AuditSealStateResult(s.getLastId(), s.getLastSeq(), s.getLastHash(), s.getSealedCount(),
                s.getAnchorSeq(), s.getUpdatedAt(), events.countBySealSeqIsNull(), events.count());
    }

    public static String canonicalV1(ChainRow row) {
        return row.id() + "|" + row.actor() + "|" + row.action() + "|" + row.target() + "|"
                + value(row.departmentId()) + "|" + value(row.applicationId()) + "|" + row.detail() + "|"
                + row.sourceIp() + "|" + row.ts();
    }

    public static String canonicalV2(long seq, ChainRow row) {
        StringBuilder text = new StringBuilder("v2");
        for (Object field : new Object[] { seq, row.id(), row.actor(), row.action(), row.target(), row.departmentId(),
                row.applicationId(), row.detail(), row.sourceIp(), row.ts() }) {
            if (field == null) {
                text.append("|-");
                continue;
            }
            String value = field.toString();
            text.append('|').append(value.length()).append(':').append(value);
        }
        return text.toString();
    }

    public static String chainHash(String prevHash, long seq, ChainRow row, long v2FromSeq) {
        return sha256(prevHash + "|" + (seq >= v2FromSeq ? canonicalV2(seq, row) : canonicalV1(row)));
    }

    private static ChainRow toChainRow(AuditEventRepository.ChainEvent e) {
        return new ChainRow(e.getId(), e.getActor(), e.getAction(), e.getTarget(), e.getDepartmentId(),
                e.getApplicationId(), e.getDetail(), e.getSourceIp(), e.getCreatedAt().getEpochSecond(),
                e.getSealSeq(), e.getPrevHash(), e.getChainHash());
    }

    private static String value(Object column) {
        return column == null ? "" : column.toString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
