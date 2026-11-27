package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AuditEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AuditEventRepository
        extends JpaRepository<AuditEvent, Long>, AuditEventRepositoryCustom {

    record Link(long seq, String hash) {}

    interface ChainEvent {
        long getId();
        String getActor();
        String getAction();
        String getTarget();
        Long getDepartmentId();
        Long getApplicationId();
        String getDetail();
        String getSourceIp();
        Instant getCreatedAt();
        Long getSealSeq();
        String getPrevHash();
        String getChainHash();
    }

    interface ChainLink {
        long getSealSeq();
        String getChainHash();
    }

    long countBySealSeqIsNull();

    long countByCreatedAtAfter(Instant since);

    @Query("""
            select e.id as id, e.actor as actor, e.action as action, e.target as target,
                   e.departmentId as departmentId, e.applicationId as applicationId,
                   e.detail as detail, e.sourceIp as sourceIp, e.createdAt as createdAt,
                   e.sealSeq as sealSeq, e.prevHash as prevHash, e.chainHash as chainHash
            from AuditEvent e where e.sealSeq is null order by e.id""")
    List<ChainEvent> findUnsealed(Limit limit);

    @Query("""
            select e.id as id, e.actor as actor, e.action as action, e.target as target,
                   e.departmentId as departmentId, e.applicationId as applicationId,
                   e.detail as detail, e.sourceIp as sourceIp, e.createdAt as createdAt,
                   e.sealSeq as sealSeq, e.prevHash as prevHash, e.chainHash as chainHash
            from AuditEvent e where e.sealSeq >= :fromSeq and e.sealSeq <= :toSeq order by e.sealSeq""")
    List<ChainEvent> findSealedRange(long fromSeq, long toSeq, Limit limit);

    @Query("select e.sealSeq as sealSeq, e.chainHash as chainHash from AuditEvent e where e.sealSeq < :seq order by e.sealSeq desc")
    List<ChainLink> findLinksBefore(long seq, Limit limit);

    @Query("select e.sealSeq from AuditEvent e where e.sealSeq is not null and e.createdAt >= :since order by e.sealSeq")
    List<Long> findFirstSeqSince(Instant since, Limit limit);

    @Query("select e.sealSeq from AuditEvent e where e.sealSeq < :belowSeq order by e.sealSeq")
    List<Long> findSeqsBelow(long belowSeq, Limit limit);

    @Query("select e.chainHash from AuditEvent e where e.sealSeq = :seq")
    List<String> findChainHashesBySealSeq(long seq, Limit limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AuditEvent e set e.sealSeq = :seq, e.prevHash = :prevHash, e.chainHash = :chainHash where e.id = :id and e.sealSeq is null")
    int sealEvent(long id, long seq, String prevHash, String chainHash);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AuditEvent e where e.sealSeq <= :seq")
    int deleteThroughSeq(long seq);

    default Optional<Link> findLinkBefore(long seq) {
        return findLinksBefore(seq, Limit.of(1)).stream()
                .findFirst().map(link -> new Link(link.getSealSeq(), link.getChainHash()));
    }

    default Optional<Long> findFirstSeqWithinDays(int days) {
        return findFirstSeqSince(JpaQuery.now().minus(days, ChronoUnit.DAYS), Limit.of(1)).stream().findFirst();
    }

    default Optional<Link> findPrefixEnd(long belowSeq, int limit) {
        var batch = findSeqsBelow(belowSeq, Limit.of(limit));
        if (batch.isEmpty()) return Optional.empty();
        long last = batch.get(batch.size() - 1);
        return findChainHashesBySealSeq(last, Limit.of(1)).stream().findFirst().map(hash -> new Link(last, hash));
    }

    @Transactional
    default void recordAudit(String actor, String action, String target, Long departmentId,
                             Long applicationId, String detail, String sourceIp) {
        saveAndFlush(new AuditEvent()
                .setActor(JpaQuery.truncate(actor, 100))
                .setAction(action)
                .setTarget(JpaQuery.truncate(target, 200))
                .setDepartmentId(departmentId)
                .setApplicationId(applicationId)
                .setDetail(JpaQuery.truncate(detail, 1000))
                .setSourceIp(JpaQuery.truncate(sourceIp, 64)));
    }
}
