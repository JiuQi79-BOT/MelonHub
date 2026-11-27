package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AccessReview;
import com.melonhub.rbac.infrastructure.persistence.entity.Application;
import com.melonhub.rbac.infrastructure.persistence.entity.Department;
import com.melonhub.rbac.service.port.security.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AccessReviewRepositoryImpl implements AccessReviewRepositoryCustom {
    private static final String REVIEW_SELECT = """
            select v.id as id, v.name as name, a.id as applicationId, a.name as applicationName, d.id as departmentId,
                   coalesce(d.name, nullif(v.departmentName, '')) as departmentName, v.status as status,
                   v.dueAt as dueAt, v.createdBy as createdBy, v.createdAt as createdAt,
                   v.closedBy as closedBy, v.closedAt as closedAt""";
    private static final String REVIEW_FROM = "from AccessReview v join v.application a left join v.department d";

    private record ReviewStats(long items, long pending, long kept, long revoked, long skipped) {
        static final ReviewStats ZERO = new ReviewStats(0, 0, 0, 0, 0);
    }

    private final EntityManager em;

    AccessReviewRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public long createReview(String name, long applicationId, Long departmentId, Instant dueAt, String createdBy) {
        Department department = departmentId == null ? null : em.find(Department.class, departmentId);
        var review = new AccessReview()
                .setName(name)
                .setApplication(em.getReference(Application.class, applicationId))
                .setDepartment(department)
                .setStatus("OPEN")
                .setDueAt(dueAt)
                .setCreatedBy(createdBy)
                .setSummary("")
                .setDepartmentName(department == null ? "" : department.getName());
        em.persist(review);
        em.flush();
        return review.getId();
    }

    @Override
    public Optional<ReviewDetailRow> findDetailById(long id) {
        var found = JpaQuery.first(em.createQuery(
                REVIEW_SELECT + ", v.summary as summary " + REVIEW_FROM + " where v.id = :id", Tuple.class)
                .setParameter("id", id));
        if (found.isEmpty()) return Optional.empty();
        Tuple t = found.get();
        var s = statsByReview(List.of(id)).getOrDefault(id, ReviewStats.ZERO);
        return Optional.of(new ReviewDetailRow(
                t.get("id", Long.class),
                t.get("name", String.class),
                t.get("applicationId", Long.class),
                t.get("applicationName", String.class),
                t.get("departmentId", Long.class),
                t.get("departmentName", String.class),
                t.get("status", String.class),
                t.get("dueAt", Instant.class),
                t.get("createdBy", String.class),
                t.get("createdAt", Instant.class),
                t.get("closedBy", String.class),
                t.get("closedAt", Instant.class),
                t.get("summary", String.class),
                s.items(), s.pending(), s.kept(), s.revoked(), s.skipped()));
    }

    @Override
    public List<ReviewSummaryRow> findVisibleReviews(Long applicationId, Visibility visible, String status) {
        var where = new JpaQuery.Where();
        if (applicationId != null) where.and("a.id = ?", applicationId);
        if (visible.applications() != null) where.in("a.id", visible.applications());
        if (visible.departments() != null) where.in("v.department.id", visible.departments());
        if (status != null && !status.isBlank()) where.and("v.status = ?", status.strip());
        var tuples = where.bind(em.createQuery(REVIEW_SELECT + " " + REVIEW_FROM + where.text + " order by v.id desc", Tuple.class))
                .setMaxResults(200).getResultList();
        if (tuples.isEmpty()) return List.of();
        var stats = statsByReview(tuples.stream().map(t -> t.get("id", Long.class)).toList());
        return tuples.stream().map(t -> {
            long reviewId = t.get("id", Long.class);
            var s = stats.getOrDefault(reviewId, ReviewStats.ZERO);
            return new ReviewSummaryRow(
                    reviewId,
                    t.get("name", String.class),
                    t.get("applicationId", Long.class),
                    t.get("applicationName", String.class),
                    t.get("departmentId", Long.class),
                    t.get("departmentName", String.class),
                    t.get("status", String.class),
                    t.get("dueAt", Instant.class),
                    t.get("createdBy", String.class),
                    t.get("createdAt", Instant.class),
                    t.get("closedBy", String.class),
                    t.get("closedAt", Instant.class),
                    s.items(), s.pending(), s.kept(), s.revoked(), s.skipped());
        }).toList();
    }

    @Override
    public Optional<String> lockStatusById(long reviewId) {
        var query = em.createQuery("select v from AccessReview v where v.id = :id", AccessReview.class)
                .setParameter("id", reviewId);
        return JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_WRITE).stream()
                .findFirst().map(AccessReview::getStatus);
    }

    @Override
    public long countOpen(Visibility visible) {
        var where = new JpaQuery.Where().and("v.status = 'OPEN'");
        if (visible.applications() != null) where.in("v.application.id", visible.applications());
        if (visible.departments() != null) where.in("v.department.id", visible.departments());
        return where.bind(em.createQuery("select count(v) from AccessReview v" + where.text, Long.class))
                .getSingleResult();
    }

    private HashMap<Long, ReviewStats> statsByReview(List<Long> ids) {
        var stats = new HashMap<Long, ReviewStats>();
        if (ids.isEmpty()) return stats;
        for (var t : em.createQuery("""
                select i.review.id as reviewId, count(i) as items,
                       sum(case when i.decision = 'PENDING' then 1 else 0 end) as pending,
                       sum(case when i.decision = 'KEEP' then 1 else 0 end) as kept,
                       sum(case when i.decision = 'REVOKE' then 1 else 0 end) as revoked,
                       sum(case when i.skipped = true then 1 else 0 end) as skipped
                from AccessReviewItem i where i.review.id in :ids group by i.review.id""", Tuple.class)
                .setParameter("ids", ids).getResultList()) {
            stats.put(t.get("reviewId", Long.class), new ReviewStats(
                    JpaQuery.num(t.get("items")),
                    JpaQuery.num(t.get("pending")),
                    JpaQuery.num(t.get("kept")),
                    JpaQuery.num(t.get("revoked")),
                    JpaQuery.num(t.get("skipped"))));
        }
        return stats;
    }
}
