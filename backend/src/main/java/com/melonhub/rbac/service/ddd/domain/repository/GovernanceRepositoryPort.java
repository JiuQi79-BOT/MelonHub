package com.melonhub.rbac.service.ddd.domain.repository;

import com.melonhub.rbac.service.ddd.domain.model.AccessRequestAggregate;
import com.melonhub.rbac.service.ddd.domain.model.AccessReviewAggregate;
import com.melonhub.rbac.service.ddd.domain.value.DomainVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 授权治理（角色申请审批与定期复核）领域仓储端口。
 */
public interface GovernanceRepositoryPort {
    record AccessRequestRecord(long id, Long userId, String userName, String externalId, Long departmentId, String departmentName,
                               long applicationId, String applicationName, Long roleId, String roleName, String roleCode,
                               Boolean roleEnabled, String reason, Integer durationDays, String status, String requestedBy,
                               String decidedBy, Instant decidedAt, String decisionComment, Instant grantExpiresAt,
                               Instant createdAt) {}

    record ReviewSummaryRecord(long id, String name, long applicationId, String applicationName, Long departmentId, String departmentName,
                               String status, Instant dueAt, String createdBy, Instant createdAt, String closedBy, Instant closedAt,
                               long items, long pending, long kept, long revoked, long skipped) {}

    record ReviewDetailRecord(long id, String name, long applicationId, String applicationName, Long departmentId, String departmentName,
                              String status, Instant dueAt, String createdBy, Instant createdAt, String closedBy, Instant closedAt,
                              String summary, long items, long pending, long kept, long revoked, long skipped) {}

    record ReviewItemRecord(long id, Long userId, String userName, String externalId, Long roleId, String roleName, String roleCode,
                            Long departmentId, String departmentName, String decision, String comment, String decidedBy,
                            Instant decidedAt, boolean applied, boolean skipped) {}

    PageSlice<AccessRequestRecord> requests(Long applicationId, DomainVisibility visibility, String status, String keyword, int page, int size);

    Optional<AccessRequestRecord> requestView(long id);

    Optional<AccessRequestAggregate> findRequest(long id);

    boolean pendingRequestExists(long userId, long roleId);

    long createRequest(long userId, long departmentId, long applicationId, long roleId, String reason, Integer durationDays, String actor);

    int decideRequest(long id, String status, String actor, String comment, Instant expiresAt);

    int cancelOwnRequest(long id, long userId);

    int cancelPendingForUser(long userId, String comment);

    int cancelPendingForRole(long roleId, String comment);

    List<AccessRequestRecord> requestsOfUser(long userId, long applicationId);

    long createReview(String name, long applicationId, Long departmentId, Instant dueAt, String actor);

    int insertReviewSnapshot(long reviewId, long applicationId, Long departmentId);

    Optional<ReviewDetailRecord> reviewView(long id);

    Optional<AccessReviewAggregate> findReview(long id);

    List<ReviewSummaryRecord> reviews(Long applicationId, DomainVisibility visibility, String status);

    PageSlice<ReviewItemRecord> reviewItems(long reviewId, String decision, int page, int size);

    Optional<String> lockReviewStatus(long reviewId);

    int decideReviewItem(long itemId, long reviewId, String decision, String actor, String comment);

    List<AccessReviewAggregate.RevocationCandidate> pendingRevocations(long reviewId);

    int revokeMatchingGrant(long userId, long roleId, String grantId);

    void markReviewItemApplied(long itemId, boolean skipped);

    int closeReview(long reviewId, String actor, String summary);
}
