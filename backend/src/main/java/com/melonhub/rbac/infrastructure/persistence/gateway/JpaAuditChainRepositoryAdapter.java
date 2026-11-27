package com.melonhub.rbac.infrastructure.persistence.gateway;

import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditSealRepository;
import com.melonhub.rbac.service.ddd.domain.model.AuditHashChainAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.AuditChainRepositoryPort;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

/** DDD 基础设施适配器：将 {@link AuditChainRepositoryPort} 桥接到 Spring Data JPA 仓储。 */
@Repository
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class JpaAuditChainRepositoryAdapter implements AuditChainRepositoryPort {
    private final AuditEventRepository events;
    private final AuditSealRepository seals;

    public JpaAuditChainRepositoryAdapter(AuditEventRepository events, AuditSealRepository seals) {
        this.events = events;
        this.seals = seals;
    }

    @Override public AuditHashChainAggregate.SealMetadata lockSeal() { return toSeal(seals.lockSeal()); }
    @Override public AuditHashChainAggregate.SealMetadata readSeal() { return toSeal(seals.readSeal()); }

    @Override
    public List<AuditHashChainAggregate.AuditEventData> findUnsealed(int limit) {
        return events.findUnsealed(Limit.of(limit)).stream().map(JpaAuditChainRepositoryAdapter::toEvent).toList();
    }

    @Override public int sealEvent(long id, long sealSeq, String prevHash, String chainHash) { return events.sealEvent(id, sealSeq, prevHash, chainHash); }
    @Override public void updateSealHead(long lastId, long lastSeq, String lastHash, long sealedCount) { seals.updateSeal(lastId, lastSeq, lastHash, sealedCount); }

    @Override
    public List<AuditHashChainAggregate.AuditEventData> findSealedSegment(long fromSeq, long toSeq, int limit) {
        return events.findSealedRange(fromSeq, toSeq, Limit.of(limit)).stream().map(JpaAuditChainRepositoryAdapter::toEvent).toList();
    }

    @Override
    public Optional<AuditHashChainAggregate.ChainLink> findLinkBefore(long seq) {
        return events.findLinkBefore(seq).map(l -> new AuditHashChainAggregate.ChainLink(l.seq(), l.hash()));
    }

    @Override public Optional<Long> findFirstSeqSinceDays(int days) { return events.findFirstSeqWithinDays(days); }

    @Override
    public Optional<AuditHashChainAggregate.ChainLink> findPrefixEnd(long belowSeq, int limit) {
        return events.findPrefixEnd(belowSeq, limit).map(l -> new AuditHashChainAggregate.ChainLink(l.seq(), l.hash()));
    }

    @Override public int deleteEventsThrough(long seq) { return events.deleteThroughSeq(seq); }
    @Override public void updateAnchor(AuditHashChainAggregate.ChainLink anchor) { seals.updateAnchor(anchor.seq(), anchor.hash()); }
    @Override public AuditHashChainAggregate.SealState sealState() { return loadSealState(seals, events); }

    static AuditHashChainAggregate.SealState loadSealState(AuditSealRepository seals, AuditEventRepository events) {
        var s = seals.findSealSummary();
        return new AuditHashChainAggregate.SealState(s.getLastId(), s.getLastSeq(), s.getLastHash(),
                s.getSealedCount(), s.getAnchorSeq(), s.getUpdatedAt(),
                events.countBySealSeqIsNull(), events.count());
    }

    private static AuditHashChainAggregate.SealMetadata toSeal(AuditSealRepository.SealSnapshot row) {
        return new AuditHashChainAggregate.SealMetadata(row.lastId(), row.lastSeq(), row.lastHash(),
                row.anchorSeq(), row.anchorHash(), row.v2FromSeq());
    }

    private static AuditHashChainAggregate.AuditEventData toEvent(AuditEventRepository.ChainEvent row) {
        return new AuditHashChainAggregate.AuditEventData(
                row.getId(), row.getActor(), row.getAction(), row.getTarget(), row.getDepartmentId(),
                row.getApplicationId(), row.getDetail(), row.getSourceIp(),
                row.getCreatedAt().getEpochSecond(), row.getSealSeq(), row.getPrevHash(), row.getChainHash());
    }
}
