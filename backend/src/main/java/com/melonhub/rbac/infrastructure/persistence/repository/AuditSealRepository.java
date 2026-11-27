package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AuditSeal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AuditSealRepository extends JpaRepository<AuditSeal, Integer>, AuditSealRepositoryCustom {
    interface SealSummary {
        long getLastId();
        long getLastSeq();
        String getLastHash();
        long getSealedCount();
        long getAnchorSeq();
        Instant getUpdatedAt();
    }

    @Query("select s.id from AuditSeal s where s.id = 1")
    Optional<Integer> checkHealth();

    @Query("""
            select s.lastId as lastId, s.lastSeq as lastSeq, s.lastHash as lastHash,
                   s.sealedCount as sealedCount, s.anchorSeq as anchorSeq, s.updatedAt as updatedAt
            from AuditSeal s where s.id = 1""")
    SealSummary findSealSummary();

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AuditSeal s set s.lastId = :lastId, s.lastSeq = :lastSeq, s.lastHash = :lastHash,
                   s.sealedCount = s.sealedCount + :sealed, s.updatedAt = current_timestamp
            where s.id = 1""")
    int updateSeal(long lastId, long lastSeq, String lastHash, long sealed);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AuditSeal s set s.anchorSeq = :anchorSeq, s.anchorHash = :anchorHash where s.id = 1")
    int updateAnchor(long anchorSeq, String anchorHash);

    default boolean isDatabaseHealthy() {
        return checkHealth().isPresent();
    }
}
