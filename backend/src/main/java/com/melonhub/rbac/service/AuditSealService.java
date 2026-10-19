package com.melonhub.rbac.service;

import com.melonhub.rbac.dao.AuditDao;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seals audit rows into a SHA-256 chain shortly after they are written. Sealing locks the one-row seal table,
 * so concurrent instances produce one consistent chain; verification can later prove no row was edited or removed,
 * including the newest rows (checked against the recorded chain end) and the oldest ones (checked against the
 * anchor that retention cleanup leaves behind).
 */
@Service
public class AuditSealService {
    private static final Logger log = LoggerFactory.getLogger(AuditSealService.class);
    private static final int BATCH = 500;
    private static final int MAX_BATCHES = 40;
    public static final int PURGE_BATCH = 5000;

    private final AuditDao dao;
    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshot;
    private final boolean scheduled;

    public AuditSealService(AuditDao dao, PlatformTransactionManager transactionManager,
                            @Value("${melonhub.maintenance.enabled:true}") boolean scheduled) {
        this.dao = dao;
        this.transactions = new TransactionTemplate(transactionManager);
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.scheduled = scheduled;
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 10_000)
    public void scheduledSeal() {
        if (scheduled) sealNow();
    }

    public int sealNow() {
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES; i++) {
                int sealed = transactions.execute(status -> sealBatch());
                total += sealed;
                if (sealed < BATCH) break;
            }
        } catch (DataAccessException e) {
            log.warn("Audit chain sealing skipped: {}", e.getClass().getSimpleName());
        }
        return total;
    }

    private int sealBatch() {
        var seal = dao.lockSeal();
        var rows = dao.unsealed(BATCH);
        String previous = seal.lastHash();
        long seq = seal.lastSeq(), lastId = seal.lastId();
        int sealed = 0;
        for (var row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String hash = AuditDao.chainHash(previous, seq + 1, row, seal.v2FromSeq());
            // A row deleted after it was read must not leave a link to nothing.
            if (dao.sealEvent(id, seq + 1, previous, hash) == 0) continue;
            seq++;
            previous = hash;
            lastId = id;
            sealed++;
        }
        if (sealed > 0) dao.updateSeal(lastId, seq, previous, sealed);
        return rows.size();
    }

    /**
     * Removes rows older than the retention period as a prefix of the chain and records the last removed link as
     * the new anchor. The newest sealed row is always kept so the chain end stays checkable.
     */
    public int purge(int days) {
        return transactions.execute(status -> {
            var seal = dao.lockSeal();
            long below = dao.firstSeqSince(days).map(seq -> Math.min(seq, seal.lastSeq())).orElse(seal.lastSeq());
            var end = dao.prefixEnd(below, PURGE_BATCH);
            if (end.isEmpty()) return 0;
            int removed = dao.deleteThrough(end.get().seq());
            dao.updateAnchor(end.get());
            return removed;
        });
    }

    /**
     * Recomputes the chain from a sequence position (the beginning when null). The first row must link to the row
     * before it, or to the retention anchor when nothing older remains. When the walk reaches the end of the chain,
     * the last row must be the recorded chain end, so deleting the newest rows, or all of them, is reported.
     */
    public Map<String,Object> verify(Long fromSeq, int limit) {
        sealNow();
        return snapshot.execute(status -> walk(fromSeq == null ? 0 : fromSeq, limit));
    }

    private Map<String,Object> walk(long start, int limit) {
        var seal = dao.seal();
        var rows = dao.sealedFrom(start, seal.lastSeq(), limit);
        var before = start > 0 ? dao.linkBefore(start) : java.util.Optional.<AuditDao.Link>empty();
        String expected = before.map(AuditDao.Link::hash).orElse(seal.anchorHash());
        long seen = before.map(AuditDao.Link::seq).orElse(seal.anchorSeq());
        long checked = 0, firstId = -1, lastId = -1, firstSeq = -1, brokenAt = -1, brokenSeq = -1;
        String problem = null;
        for (var row : rows) {
            long id = ((Number) row.get("id")).longValue(), seq = ((Number) row.get("seal_seq")).longValue();
            String previous = (String) row.get("prev_hash");
            if (!equals(previous, expected)) problem = "LINK_BROKEN";
            else if (!equals((String) row.get("chain_hash"), AuditDao.chainHash(previous, seq, row, seal.v2FromSeq()))) problem = "MODIFIED";
            if (problem != null) { brokenAt = id; brokenSeq = seq; break; }
            expected = (String) row.get("chain_hash");
            seen = seq;
            if (firstId < 0) { firstId = id; firstSeq = seq; }
            lastId = id;
            checked++;
        }
        boolean complete = problem == null && (rows.size() < limit || seen >= seal.lastSeq());
        if (complete && (seen != seal.lastSeq() || !equals(expected, seal.lastHash()))) problem = "TAIL_MISSING";
        var result = new LinkedHashMap<String,Object>();
        result.put("ok", problem == null);
        result.put("problem", problem);
        result.put("checked", checked);
        result.put("firstSeq", firstSeq < 0 ? null : firstSeq);
        result.put("lastSeq", checked == 0 ? null : seen);
        result.put("firstId", firstId < 0 ? null : firstId);
        result.put("lastId", lastId < 0 ? null : lastId);
        result.put("brokenAt", brokenAt < 0 ? null : brokenAt);
        result.put("brokenSeq", brokenSeq < 0 ? null : brokenSeq);
        result.put("complete", complete);
        result.put("nextSeq", problem == null && !complete ? seen + 1 : null);
        result.put("seal", dao.sealState());
        return result;
    }

    private static boolean equals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
