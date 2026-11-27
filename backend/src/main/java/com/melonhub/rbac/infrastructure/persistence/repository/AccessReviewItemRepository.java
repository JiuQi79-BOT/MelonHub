package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AccessReview;
import com.melonhub.rbac.infrastructure.persistence.entity.AccessReviewItem;
import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AccessReviewItemRepository extends JpaRepository<AccessReviewItem, Long> {
    record ReviewItemRow(long id, Long userId, String userName, String externalId, Long roleId, String roleName,
                         String roleCode, Long departmentId, String departmentName, String decision,
                         String comment, String decidedBy, Instant decidedAt, boolean applied, boolean skipped) {}

    interface RevocationCandidate {
        long getId();
        Long getUserId();
        Long getRoleId();
        Long getDepartmentId();
        String getGrantId();
    }

    interface SnapshotSource {
        long getUserId();
        long getRoleId();
        long getDepartmentId();
        String getGrantId();
        String getUserName();
        String getExternalId();
        String getRoleCode();
        String getRoleName();
        String getDepartmentName();
    }

    interface ItemProjection {
        long getId();
        Long getUserId();
        String getUserName();
        String getExternalId();
        Long getRoleId();
        String getRoleName();
        String getRoleCode();
        Long getDepartmentId();
        String getDepartmentName();
        String getDecision();
        String getComment();
        String getDecidedBy();
        Instant getDecidedAt();
        boolean isApplied();
        boolean isSkipped();
    }

    @Query("""
            select u.id as userId, r.id as roleId, ur.departmentId as departmentId, ur.grantId as grantId,
                   u.name as userName, u.externalId as externalId, r.code as roleCode, r.name as roleName,
                   d.name as departmentName
            from UserRole ur join ur.user u join ur.role r join ur.department d
            where ur.applicationId = :applicationId
              and (ur.expiresAt is null or ur.expiresAt > current_timestamp)
              and (:departmentId is null or ur.departmentId = :departmentId)
            order by ur.departmentId, u.id, r.id""")
    List<SnapshotSource> findSnapshotSources(long applicationId, Long departmentId);

    @Query(value = """
            select i.id as id, u.id as userId, coalesce(u.name, i.userName) as userName,
                   coalesce(u.externalId, i.externalId) as externalId, r.id as roleId,
                   coalesce(r.name, i.roleName) as roleName, coalesce(r.code, i.roleCode) as roleCode,
                   i.departmentId as departmentId, coalesce(d.name, i.departmentName) as departmentName,
                   i.decision as decision, i.comment as comment, i.decidedBy as decidedBy,
                   i.decidedAt as decidedAt, i.applied as applied, i.skipped as skipped
            from AccessReviewItem i left join i.user u left join i.role r left join i.department d
            where i.review.id = :reviewId and (:decision is null or i.decision = :decision)
            order by i.id desc""",
            countQuery = """
            select count(i) from AccessReviewItem i
            where i.review.id = :reviewId and (:decision is null or i.decision = :decision)""")
    Page<ItemProjection> findItemsPageProjections(long reviewId, String decision, Pageable pageable);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AccessReviewItem i set i.decision = :decision, i.decidedBy = :decidedBy,
                   i.decidedAt = current_timestamp, i.comment = :comment
            where i.id = :itemId and i.review.id = :reviewId and i.applied = false""")
    int decideItem(long itemId, long reviewId, String decision, String decidedBy, String comment);

    @Query("""
            select i.id as id, i.user.id as userId, i.role.id as roleId,
                   i.departmentId as departmentId, i.grantId as grantId
            from AccessReviewItem i
            where i.review.id = :reviewId and i.decision = 'REVOKE' and i.applied = false
            order by i.id""")
    List<RevocationCandidate> findPendingRevocations(long reviewId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AccessReviewItem i set i.applied = true, i.skipped = :skipped where i.id = :itemId")
    int markApplied(long itemId, boolean skipped);

    @Transactional
    default int insertSnapshot(long applicationId, Long departmentId, AccessReview reviewRef,
                               LongFunction<User> userRef, LongFunction<Role> roleRef) {
        var assignments = findSnapshotSources(applicationId, departmentId);
        if (assignments.isEmpty()) return 0;
        var batch = new ArrayList<AccessReviewItem>(Math.min(500, assignments.size()));
        for (var a : assignments) {
            batch.add(new AccessReviewItem()
                    .setReview(reviewRef)
                    .setUser(userRef.apply(a.getUserId()))
                    .setRole(roleRef.apply(a.getRoleId()))
                    .setDepartmentId(a.getDepartmentId())
                    .setGrantId(a.getGrantId())
                    .setUserName(a.getUserName())
                    .setExternalId(a.getExternalId())
                    .setRoleCode(a.getRoleCode())
                    .setRoleName(a.getRoleName())
                    .setDepartmentName(a.getDepartmentName())
                    .setDecision("PENDING")
                    .setComment(""));
            if (batch.size() == 500) {
                saveAllAndFlush(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            saveAllAndFlush(batch);
        }
        return assignments.size();
    }

    default Page<ReviewItemRow> findItemsPage(long reviewId, String decision, int page, int size) {
        return findItemsPageProjections(reviewId, RepositorySupport.textOrNull(decision), RepositorySupport.page(page, size))
                .map(p -> new ReviewItemRow(
                        p.getId(), p.getUserId(), p.getUserName(), p.getExternalId(), p.getRoleId(),
                        p.getRoleName(), p.getRoleCode(), p.getDepartmentId(), p.getDepartmentName(),
                        p.getDecision(), p.getComment(), p.getDecidedBy(), p.getDecidedAt(),
                        p.isApplied(), p.isSkipped()));
    }
}
