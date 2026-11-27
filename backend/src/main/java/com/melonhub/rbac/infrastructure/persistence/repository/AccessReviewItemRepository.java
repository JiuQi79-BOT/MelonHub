package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AccessReviewItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AccessReviewItemRepository
        extends JpaRepository<AccessReviewItem, Long>, AccessReviewItemRepositoryCustom {

    interface RevocationCandidate {
        long getId();
        Long getUserId();
        Long getRoleId();
        Long getDepartmentId();
        String getGrantId();
    }

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
}
