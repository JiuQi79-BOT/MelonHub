package com.melonhub.rbac.infrastructure.persistence.repository;

import java.time.Instant;
import org.springframework.data.domain.Page;

/** 权限复核条目的快照生成与动态分页查询片段。 */
public interface AccessReviewItemRepositoryCustom {
    record ReviewItemRow(long id, Long userId, String userName, String externalId, Long roleId, String roleName,
                         String roleCode, Long departmentId, String departmentName, String decision,
                         String comment, String decidedBy, Instant decidedAt, boolean applied, boolean skipped) {}

    int insertSnapshot(long reviewId, long applicationId, Long departmentId);

    Page<ReviewItemRow> findItemsPage(long reviewId, String decision, int page, int size);
}
