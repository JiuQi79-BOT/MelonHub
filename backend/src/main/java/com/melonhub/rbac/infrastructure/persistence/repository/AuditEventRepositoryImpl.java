package com.melonhub.rbac.infrastructure.persistence.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AuditEventRepositoryImpl implements AuditEventRepositoryCustom {
    private static final List<String> COURSE_AUDIT_TYPES =
            List.of("score_changed", "group_changed", "milestone_changed", "roster_changed");

    private final EntityManager em;

    AuditEventRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public Page<AuditEventRow> findAuditPage(AuditFilter filter, int page, int size) {
        var where = new JpaQuery.Where();
        if (filter.departmentId() != null) where.and("e.departmentId = ?", filter.departmentId());
        if (filter.applicationId() != null) where.and("e.applicationId = ?", filter.applicationId());
        if (filter.visible().applications() != null) where.in("e.applicationId", filter.visible().applications());
        if (filter.visible().departments() != null) where.in("e.departmentId", filter.visible().departments());
        if (filter.actor() != null && !filter.actor().isBlank()) where.and("e.actor = ?", filter.actor().strip());
        if (filter.action() != null && !filter.action().isBlank()) where.and("e.action = ?", filter.action().strip());
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            String pattern = JpaQuery.like(filter.keyword().strip());
            where.and("(e.target like ? escape '!' or e.detail like ? escape '!' or e.actor like ? escape '!')",
                    pattern, pattern, pattern);
        }
        if (filter.from() != null) where.and("e.createdAt >= ?", filter.from());
        if (filter.to() != null) where.and("e.createdAt < ?", filter.to());
        return JpaQuery.page(em, """
                select e.id as id, e.actor as actor, e.action as action, e.target as target, e.detail as detail,
                       e.sourceIp as sourceIp, e.createdAt as createdAt, e.departmentId as departmentId,
                       d.name as departmentName, e.applicationId as applicationId, a.name as applicationName""",
                "from AuditEvent e left join e.department d left join e.application a",
                where, "e.id desc", page, size,
                t -> new AuditEventRow(
                        t.get("id", Long.class),
                        t.get("actor", String.class),
                        t.get("action", String.class),
                        t.get("target", String.class),
                        t.get("detail", String.class),
                        t.get("sourceIp", String.class),
                        t.get("createdAt", Instant.class),
                        t.get("departmentId", Long.class),
                        t.get("departmentName", String.class),
                        t.get("applicationId", Long.class),
                        t.get("applicationName", String.class)));
    }

    @Override
    public Page<CourseAuditRow> findCourseAuditPage(String applicationCode, List<Long> departmentIds, String actor,
                                                    Long actorId, String target, Instant from, Instant to,
                                                    int page, int pageSize) {
        var where = new JpaQuery.Where()
                .and("a.code = ?", applicationCode)
                .in("e.departmentId", departmentIds)
                .in("e.action", COURSE_AUDIT_TYPES);
        if (actor != null && !actor.isBlank()) {
            String pattern = JpaQuery.like(actor.strip());
            where.and("(e.actor like ? escape '!' or u.name like ? escape '!')", pattern, pattern);
        }
        if (actorId != null) where.and("u.id = ?", actorId);
        if (target != null && !target.isBlank()) where.and("e.target like ? escape '!'", JpaQuery.like(target.strip()));
        if (from != null) where.and("e.createdAt >= ?", from);
        if (to != null) where.and("e.createdAt <= ?", to);
        return JpaQuery.page(em, """
                select e.id as id, e.createdAt as at, e.action as type, e.target as target, e.actor as actorNo,
                       u.id as actorId, u.name as actorName""",
                "from AuditEvent e join e.application a left join User u on u.externalId = e.actor",
                where, "e.createdAt desc, e.id desc", page, pageSize,
                t -> new CourseAuditRow(
                        t.get("id", Long.class),
                        t.get("at", Instant.class),
                        t.get("type", String.class),
                        t.get("target", String.class),
                        t.get("actorNo", String.class),
                        t.get("actorId", Long.class),
                        t.get("actorName", String.class)));
    }
}
