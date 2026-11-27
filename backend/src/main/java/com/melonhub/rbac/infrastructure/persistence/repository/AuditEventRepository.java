package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AuditEvent;
import com.melonhub.rbac.service.port.security.Visibility;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<String> COURSE_AUDIT_TYPES = List.of("score_changed", "group_changed", "milestone_changed", "roster_changed");

    record Link(long seq, String hash) {}

    record AuditEventRow(long id, String actor, String action, String target, String detail, String sourceIp,
                         Instant createdAt, Long departmentId, String departmentName,
                         Long applicationId, String applicationName) {}

    record AuditFilter(Long departmentId, Long applicationId, Visibility visible, String actor, String action,
                       String keyword, Instant from, Instant to) {}

    record CourseAuditRow(long id, Instant at, String type, String target, String actorNo,
                          Long actorId, String actorName) {}

    interface AuditRowProjection {
        long getId();
        String getActor();
        String getAction();
        String getTarget();
        String getDetail();
        String getSourceIp();
        Instant getCreatedAt();
        Long getDepartmentId();
        String getDepartmentName();
        Long getApplicationId();
        String getApplicationName();
    }

    interface CourseAuditProjection {
        long getId();
        Instant getAt();
        String getType();
        String getTarget();
        String getActorNo();
        Long getActorId();
        String getActorName();
    }

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

    @Query(value = """
            select e.id as id, e.actor as actor, e.action as action, e.target as target, e.detail as detail,
                   e.sourceIp as sourceIp, e.createdAt as createdAt, e.departmentId as departmentId,
                   d.name as departmentName, e.applicationId as applicationId, a.name as applicationName
            from AuditEvent e left join e.department d left join e.application a
            where (:departmentId is null or e.departmentId = :departmentId)
              and (:applicationId is null or e.applicationId = :applicationId)
              and (:filterApps = false or e.applicationId in :appIds)
              and (:filterDepts = false or e.departmentId in :deptIds)
              and (:actor is null or e.actor = :actor)
              and (:action is null or e.action = :action)
              and (:pattern is null
                   or e.target like :pattern escape '!'
                   or e.detail like :pattern escape '!'
                   or e.actor like :pattern escape '!')
              and (:from is null or e.createdAt >= :from)
              and (:to is null or e.createdAt < :to)
            order by e.id desc""",
            countQuery = """
            select count(e) from AuditEvent e
            where (:departmentId is null or e.departmentId = :departmentId)
              and (:applicationId is null or e.applicationId = :applicationId)
              and (:filterApps = false or e.applicationId in :appIds)
              and (:filterDepts = false or e.departmentId in :deptIds)
              and (:actor is null or e.actor = :actor)
              and (:action is null or e.action = :action)
              and (:pattern is null
                   or e.target like :pattern escape '!'
                   or e.detail like :pattern escape '!'
                   or e.actor like :pattern escape '!')
              and (:from is null or e.createdAt >= :from)
              and (:to is null or e.createdAt < :to)""")
    Page<AuditRowProjection> findAuditPageProjections(Long departmentId, Long applicationId,
                                                      boolean filterApps, Collection<Long> appIds,
                                                      boolean filterDepts, Collection<Long> deptIds,
                                                      String actor, String action, String pattern,
                                                      Instant from, Instant to, Pageable pageable);

    @Query(value = """
            select e.id as id, e.createdAt as at, e.action as type, e.target as target, e.actor as actorNo,
                   u.id as actorId, u.name as actorName
            from AuditEvent e join e.application a left join User u on u.externalId = e.actor
            where a.code = :applicationCode
              and e.departmentId in :departmentIds
              and e.action in :actions
              and (:actorPattern is null or e.actor like :actorPattern escape '!' or u.name like :actorPattern escape '!')
              and (:actorId is null or u.id = :actorId)
              and (:targetPattern is null or e.target like :targetPattern escape '!')
              and (:from is null or e.createdAt >= :from)
              and (:to is null or e.createdAt <= :to)
            order by e.createdAt desc, e.id desc""",
            countQuery = """
            select count(e)
            from AuditEvent e join e.application a left join User u on u.externalId = e.actor
            where a.code = :applicationCode
              and e.departmentId in :departmentIds
              and e.action in :actions
              and (:actorPattern is null or e.actor like :actorPattern escape '!' or u.name like :actorPattern escape '!')
              and (:actorId is null or u.id = :actorId)
              and (:targetPattern is null or e.target like :targetPattern escape '!')
              and (:from is null or e.createdAt >= :from)
              and (:to is null or e.createdAt <= :to)""")
    Page<CourseAuditProjection> findCourseAuditPageProjections(String applicationCode, Collection<Long> departmentIds,
                                                               Collection<String> actions, String actorPattern,
                                                               Long actorId, String targetPattern,
                                                               Instant from, Instant to, Pageable pageable);

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

    default Page<AuditEventRow> findAuditPage(AuditFilter filter, int page, int size) {
        var pageable = RepositorySupport.page(page, size);
        boolean filterApps = filter.visible().applications() != null;
        boolean filterDepts = filter.visible().departments() != null;
        if ((filterApps && filter.visible().applications().isEmpty())
                || (filterDepts && filter.visible().departments().isEmpty())) {
            return Page.empty(pageable);
        }
        return findAuditPageProjections(filter.departmentId(), filter.applicationId(),
                filterApps, filterApps ? filter.visible().applications() : RepositorySupport.NO_IDS,
                filterDepts, filterDepts ? filter.visible().departments() : RepositorySupport.NO_IDS,
                RepositorySupport.textOrNull(filter.actor()), RepositorySupport.textOrNull(filter.action()),
                RepositorySupport.likeOrNull(filter.keyword()), filter.from(), filter.to(), pageable)
                .map(p -> new AuditEventRow(
                        p.getId(), p.getActor(), p.getAction(), p.getTarget(), p.getDetail(), p.getSourceIp(),
                        p.getCreatedAt(), p.getDepartmentId(), p.getDepartmentName(),
                        p.getApplicationId(), p.getApplicationName()));
    }

    default Page<CourseAuditRow> findCourseAuditPage(String applicationCode, List<Long> departmentIds, String actor,
                                                     Long actorId, String target, Instant from, Instant to,
                                                     int page, int pageSize) {
        var pageable = RepositorySupport.page(page, pageSize);
        if (departmentIds == null || departmentIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return findCourseAuditPageProjections(applicationCode, departmentIds, COURSE_AUDIT_TYPES,
                RepositorySupport.likeOrNull(actor), actorId, RepositorySupport.likeOrNull(target),
                from, to, pageable)
                .map(p -> new CourseAuditRow(
                        p.getId(), p.getAt(), p.getType(), p.getTarget(),
                        p.getActorNo(), p.getActorId(), p.getActorName()));
    }

    default Optional<Link> findLinkBefore(long seq) {
        return findLinksBefore(seq, Limit.of(1)).stream()
                .findFirst().map(link -> new Link(link.getSealSeq(), link.getChainHash()));
    }

    default Optional<Long> findFirstSeqWithinDays(int days) {
        return findFirstSeqSince(RepositorySupport.now().minus(days, ChronoUnit.DAYS), Limit.of(1)).stream().findFirst();
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
                .setActor(RepositorySupport.truncate(actor, 100))
                .setAction(action)
                .setTarget(RepositorySupport.truncate(target, 200))
                .setDepartmentId(departmentId)
                .setApplicationId(applicationId)
                .setDetail(RepositorySupport.truncate(detail, 1000))
                .setSourceIp(RepositorySupport.truncate(sourceIp, 64)));
    }
}
