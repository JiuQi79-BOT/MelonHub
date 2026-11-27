package com.melonhub.rbac.service.ddd.domain.model;

import com.melonhub.rbac.service.ddd.domain.value.AuditCanonicalizer;
import java.time.Instant;
import java.util.List;

/**
 * 审计哈希链聚合根：封装按封存序连续计算 SHA-256 链哈希、前缀裁剪边界以及链路篡改/断链/尾缺检测状态机。
 */
public final class AuditHashChainAggregate {
    public record SealMetadata(long lastId, long lastSeq, String lastHash, long anchorSeq, String anchorHash, long v2FromSeq) {}

    public record SealState(long lastId, long lastSeq, String lastHash, long sealedCount, long anchorSeq,
                            Instant updatedAt, long unsealed, long total) {}

    public record ChainLink(long seq, String hash) {}

    public record AuditEventData(
            long id,
            String actor,
            String action,
            String target,
            Long departmentId,
            Long applicationId,
            String detail,
            String sourceIp,
            Long epochSeconds,
            Long sealSeq,
            String prevHash,
            String chainHash
    ) {}

    public record VerificationResult(
            boolean ok,
            String problem,
            long checked,
            Long firstSeq,
            Long lastSeq,
            Long firstId,
            Long lastId,
            Long brokenAt,
            Long brokenSeq,
            boolean complete,
            Long nextSeq
    ) {}

    private final SealMetadata seal;

    public AuditHashChainAggregate(SealMetadata seal) {
        this.seal = seal;
    }

    public String computeHash(String previousHash, long sequence, AuditEventData event) {
        return AuditCanonicalizer.chainHash(previousHash, sequence, seal.v2FromSeq(),
                event.id(), event.actor(), event.action(), event.target(),
                event.departmentId(), event.applicationId(), event.detail(), event.sourceIp(), event.epochSeconds());
    }

    public long purgeUpperBoundSeq(Long firstSeqWithinRetention) {
        return firstSeqWithinRetention == null ? seal.lastSeq() : Math.min(firstSeqWithinRetention, seal.lastSeq());
    }

    public VerificationResult verifySegment(List<AuditEventData> rows, ChainLink previousLink, int limit) {
        String expected = previousLink != null ? previousLink.hash() : seal.anchorHash();
        long seen = previousLink != null ? previousLink.seq() : seal.anchorSeq();
        long checked = 0, firstId = -1, lastId = -1, firstSeq = -1, brokenAt = -1, brokenSeq = -1;
        String problem = null;
        for (var row : rows) {
            long id = row.id();
            long seq = row.sealSeq();
            if (!AuditCanonicalizer.constantTimeEquals(row.prevHash(), expected)) {
                problem = "LINK_BROKEN";
            } else if (!AuditCanonicalizer.constantTimeEquals(row.chainHash(), computeHash(row.prevHash(), seq, row))) {
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
        if (complete && (seen != seal.lastSeq() || !AuditCanonicalizer.constantTimeEquals(expected, seal.lastHash()))) {
            problem = "TAIL_MISSING";
        }
        return new VerificationResult(
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
                problem == null && !complete ? seen + 1 : null);
    }
}
