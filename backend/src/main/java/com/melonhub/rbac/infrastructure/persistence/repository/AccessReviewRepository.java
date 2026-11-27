package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AccessReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AccessReviewRepository
        extends JpaRepository<AccessReview, Long>, AccessReviewRepositoryCustom {

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AccessReview v set v.status = 'CLOSED', v.closedBy = :closedBy,
                   v.closedAt = current_timestamp, v.summary = :summary
            where v.id = :reviewId and v.status = 'OPEN'""")
    int closeOpenReview(long reviewId, String closedBy, String summary);
}
