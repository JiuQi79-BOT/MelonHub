package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AccessReview;
import com.melonhub.rbac.infrastructure.persistence.entity.Application;
import com.melonhub.rbac.infrastructure.persistence.entity.Department;
import com.melonhub.rbac.service.port.security.Visibility;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AccessReviewRepository extends JpaRepository<AccessReview, Long> {
    record ReviewSummaryRow(long id, String name, long applicationId, String applicationName, Long departmentId,
                            String departmentName, String status, Instant dueAt, String createdBy,
                            Instant createdAt, String closedBy, Instant closedAt,
                            long items, long pending, long kept, long revoked, long skipped) {}

    record ReviewDetailRow(long id, String name, long applicationId, String applicationName, Long departmentId,
                           String departmentName, String status, Instant dueAt, String createdBy,
                           Instant createdAt, String closedBy, Instant closedAt, String summary,
                           long items, long pending, long kept, long revoked, long skipped) {}

    interface ReviewBaseProjection {
        long getId();
        String getName();
        long getApplicationId();
        String getApplicationName();
        Long getDepartmentId();
        String getDepartmentName();
        String getStatus();
        Instant getDueAt();
        String getCreatedBy();
        Instant getCreatedAt();
        String getClosedBy();
        Instant getClosedAt();
    }

    interface ReviewDetailProjection extends ReviewBaseProjection {
        String getSummary();
    }

    interface ReviewStatsProjection {
        long getReviewId();
        long getItems();
        long getPending();
        long getKept();
        long getRevoked();
        long getSkipped();
    }

    String REVIEW_COLUMNS = """
            v.id as id, v.name as name, a.id as applicationId, a.name as applicationName, d.id as departmentId,
            coalesce(d.name, nullif(v.departmentName, '')) as departmentName, v.status as status,
            v.dueAt as dueAt, v.createdBy as createdBy, v.createdAt as createdAt,
            v.closedBy as closedBy, v.closedAt as closedAt""";

    @Query("select " + REVIEW_COLUMNS + ", v.summary as summary from AccessReview v join v.application a left join v.department d where v.id = :id")
    Optional<ReviewDetailProjection> findDetailProjectionById(long id);

    @Query("select " + REVIEW_COLUMNS + " " + """
            from AccessReview v join v.application a left join v.department d
            where (:applicationId is null or a.id = :applicationId)
              and (:filterApps = false or a.id in :appIds)
              and (:filterDepts = false or v.department.id in :deptIds)
              and (:status is null or v.status = :status)
            order by v.id desc""")
    List<ReviewBaseProjection> findVisibleBaseProjections(Long applicationId, boolean filterApps, Collection<Long> appIds,
                                                          boolean filterDepts, Collection<Long> deptIds,
                                                          String status, Limit limit);

    @Query("""
            select i.review.id as reviewId, count(i) as items,
                   coalesce(sum(case when i.decision = 'PENDING' then 1L else 0L end), 0L) as pending,
                   coalesce(sum(case when i.decision = 'KEEP' then 1L else 0L end), 0L) as kept,
                   coalesce(sum(case when i.decision = 'REVOKE' then 1L else 0L end), 0L) as revoked,
                   coalesce(sum(case when i.skipped = true then 1L else 0L end), 0L) as skipped
            from AccessReviewItem i where i.review.id in :ids group by i.review.id""")
    List<ReviewStatsProjection> findStatsByReviewIds(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from AccessReview v where v.id = :reviewId")
    Optional<AccessReview> findLockedById(long reviewId);

    @Query("""
            select count(v) from AccessReview v
            where v.status = 'OPEN'
              and (:filterApps = false or v.application.id in :appIds)
              and (:filterDepts = false or v.department.id in :deptIds)""")
    long countOpenRows(boolean filterApps, Collection<Long> appIds, boolean filterDepts, Collection<Long> deptIds);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AccessReview v set v.status = 'CLOSED', v.closedBy = :closedBy,
                   v.closedAt = current_timestamp, v.summary = :summary
            where v.id = :reviewId and v.status = 'OPEN'""")
    int closeOpenReview(long reviewId, String closedBy, String summary);

    @Transactional
    default long createReview(String name, Application application, Department department,
                              Instant dueAt, String createdBy) {
        return saveAndFlush(new AccessReview()
                .setName(name)
                .setApplication(application)
                .setDepartment(department)
                .setStatus("OPEN")
                .setDueAt(dueAt)
                .setCreatedBy(createdBy)
                .setSummary("")
                .setDepartmentName(department == null ? "" : department.getName())).getId();
    }

    default Optional<ReviewDetailRow> findDetailById(long id) {
        var found = findDetailProjectionById(id);
        if (found.isEmpty()) return Optional.empty();
        var p = found.get();
        var stats = indexStats(List.of(id)).get(id);
        return Optional.of(new ReviewDetailRow(
                p.getId(), p.getName(), p.getApplicationId(), p.getApplicationName(), p.getDepartmentId(),
                p.getDepartmentName(), p.getStatus(), p.getDueAt(), p.getCreatedBy(), p.getCreatedAt(),
                p.getClosedBy(), p.getClosedAt(), p.getSummary(),
                stats == null ? 0L : stats.getItems(),
                stats == null ? 0L : stats.getPending(),
                stats == null ? 0L : stats.getKept(),
                stats == null ? 0L : stats.getRevoked(),
                stats == null ? 0L : stats.getSkipped()));
    }

    default List<ReviewSummaryRow> findVisibleReviews(Long applicationId, Visibility visible, String status) {
        boolean filterApps = visible.applications() != null;
        boolean filterDepts = visible.departments() != null;
        if ((filterApps && visible.applications().isEmpty()) || (filterDepts && visible.departments().isEmpty())) {
            return List.of();
        }
        var rows = findVisibleBaseProjections(applicationId,
                filterApps, filterApps ? visible.applications() : RepositorySupport.NO_IDS,
                filterDepts, filterDepts ? visible.departments() : RepositorySupport.NO_IDS,
                RepositorySupport.textOrNull(status), Limit.of(200));
        if (rows.isEmpty()) return List.of();
        var statsMap = indexStats(rows.stream().map(ReviewBaseProjection::getId).toList());
        return rows.stream().map(p -> {
            var stats = statsMap.get(p.getId());
            return new ReviewSummaryRow(
                    p.getId(), p.getName(), p.getApplicationId(), p.getApplicationName(), p.getDepartmentId(),
                    p.getDepartmentName(), p.getStatus(), p.getDueAt(), p.getCreatedBy(), p.getCreatedAt(),
                    p.getClosedBy(), p.getClosedAt(),
                    stats == null ? 0L : stats.getItems(),
                    stats == null ? 0L : stats.getPending(),
                    stats == null ? 0L : stats.getKept(),
                    stats == null ? 0L : stats.getRevoked(),
                    stats == null ? 0L : stats.getSkipped());
        }).toList();
    }

    @Transactional
    default Optional<String> lockStatusById(long reviewId) {
        return findLockedById(reviewId).map(AccessReview::getStatus);
    }

    default long countOpen(Visibility visible) {
        boolean filterApps = visible.applications() != null;
        boolean filterDepts = visible.departments() != null;
        if ((filterApps && visible.applications().isEmpty()) || (filterDepts && visible.departments().isEmpty())) {
            return 0L;
        }
        return countOpenRows(filterApps, filterApps ? visible.applications() : RepositorySupport.NO_IDS,
                filterDepts, filterDepts ? visible.departments() : RepositorySupport.NO_IDS);
    }

    private Map<Long, ReviewStatsProjection> indexStats(List<Long> ids) {
        var map = new HashMap<Long, ReviewStatsProjection>();
        if (ids.isEmpty()) return map;
        for (var s : findStatsByReviewIds(ids)) {
            map.put(s.getReviewId(), s);
        }
        return map;
    }
}
