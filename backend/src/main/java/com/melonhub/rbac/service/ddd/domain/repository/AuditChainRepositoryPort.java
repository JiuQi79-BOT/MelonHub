package com.melonhub.rbac.service.ddd.domain.repository;

import com.melonhub.rbac.service.ddd.domain.model.AuditHashChainAggregate;
import java.util.List;
import java.util.Optional;

/**
 * 审计哈希链封存、前缀裁剪与校验领域仓储端口。
 */
public interface AuditChainRepositoryPort {
    AuditHashChainAggregate.SealMetadata lockSeal();

    AuditHashChainAggregate.SealMetadata readSeal();

    List<AuditHashChainAggregate.AuditEventData> findUnsealed(int limit);

    int sealEvent(long id, long sealSeq, String prevHash, String chainHash);

    void updateSealHead(long lastId, long lastSeq, String lastHash, long sealedCount);

    List<AuditHashChainAggregate.AuditEventData> findSealedSegment(long fromSeq, long toSeq, int limit);

    Optional<AuditHashChainAggregate.ChainLink> findLinkBefore(long seq);

    Optional<Long> findFirstSeqSinceDays(int days);

    Optional<AuditHashChainAggregate.ChainLink> findPrefixEnd(long belowSeq, int limit);

    int deleteEventsThrough(long seq);

    void updateAnchor(AuditHashChainAggregate.ChainLink anchor);

    AuditHashChainAggregate.SealState sealState();
}
