package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.service.port.security.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 权限复核的快照创建、加锁状态读取与统计查询片段。 */
public interface AccessReviewRepositoryCustom {
    record ReviewSummaryRow(long id, String name, long applicationId, String applicationName, Long departmentId,
                            String departmentName, String status, Instant dueAt, String createdBy,
                            Instant createdAt, String closedBy, Instant closedAt,
                            long items, long pending, long kept, long revoked, long skipped) {}

    record ReviewDetailRow(long id, String name, long applicationId, String applicationName, Long departmentId,
                           String departmentName, String status, Instant dueAt, String createdBy,
                           Instant createdAt, String closedBy, Instant closedAt, String summary,
                           long items, long pending, long kept, long revoked, long skipped) {}

    long createReview(String name, long applicationId, Long departmentId, Instant dueAt, String createdBy);

    Optional<ReviewDetailRow> findDetailById(long id);

    List<ReviewSummaryRow> findVisibleReviews(Long applicationId, Visibility visible, String status);

    Optional<String> lockStatusById(long reviewId);

    long countOpen(Visibility visible);
}
