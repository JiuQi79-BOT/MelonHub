package com.melonhub.rbac.infrastructure.persistence.gateway;

import static com.melonhub.rbac.infrastructure.persistence.gateway.JpaRbacRepositoryAdapter.toVisibility;

import com.melonhub.rbac.infrastructure.persistence.repository.AccessRequestRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessReviewItemRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessReviewRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRoleRepository;
import com.melonhub.rbac.service.ddd.domain.model.AccessRequestAggregate;
import com.melonhub.rbac.service.ddd.domain.model.AccessReviewAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.GovernanceRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PageSlice;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.DomainVisibility;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** DDD 基础设施适配器：将 {@link GovernanceRepositoryPort} 桥接到 Spring Data JPA 仓储。 */
@Repository
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class JpaGovernanceRepositoryAdapter implements GovernanceRepositoryPort {
    private final AccessRequestRepository requests;
    private final AccessReviewRepository reviews;
    private final AccessReviewItemRepository reviewItems;
    private final UserRoleRepository userRoles;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final ApplicationRepository applications;
    private final RoleRepository roles;

    public JpaGovernanceRepositoryAdapter(AccessRequestRepository requests, AccessReviewRepository reviews,
                                          AccessReviewItemRepository reviewItems, UserRoleRepository userRoles,
                                          UserRepository users, DepartmentRepository departments,
                                          ApplicationRepository applications, RoleRepository roles) {
        this.requests = requests;
        this.reviews = reviews;
        this.reviewItems = reviewItems;
        this.userRoles = userRoles;
        this.users = users;
        this.departments = departments;
        this.applications = applications;
        this.roles = roles;
    }

    @Override
    public PageSlice<AccessRequestRecord> requests(Long applicationId, DomainVisibility visibility, String status, String keyword, int page, int size) {
        var p = requests.findPage(applicationId, toVisibility(visibility), status, keyword, page, size);
        return new PageSlice<>(p.getContent().stream().map(JpaGovernanceRepositoryAdapter::toRequestRecord).toList(), p.getTotalElements());
    }

    @Override
    public Optional<AccessRequestRecord> requestView(long id) {
        return requests.findDetailById(id).map(JpaGovernanceRepositoryAdapter::toRequestRecord);
    }

    @Override
    public Optional<AccessRequestAggregate> findRequest(long id) {
        return requests.findDetailById(id).map(row -> new AccessRequestAggregate(
                row.id(), row.status(), row.userId(), row.departmentId(), row.applicationId(), row.roleId(),
                Boolean.TRUE.equals(row.roleEnabled()), row.externalId(), row.roleCode(), row.durationDays()));
    }

    @Override public boolean pendingRequestExists(long userId, long roleId) { return requests.existsPending(userId, roleId); }

    @Override
    public long createRequest(long userId, long departmentId, long applicationId, long roleId, String reason, Integer durationDays, String actor) {
        try {
            return requests.createRequest(
                    users.findById(userId).orElseThrow(),
                    departments.findById(departmentId).orElseThrow(),
                    applications.getReferenceById(applicationId),
                    roles.findById(roleId).orElseThrow(),
                    reason, durationDays, actor);
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateKey(e)) throw DomainException.conflict("该用户已有此角色的待审批申请");
            throw e;
        }
    }

    private static boolean isDuplicateKey(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) return true;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) return true;
        }
        return false;
    }

    @Override
    public int decideRequest(long id, String status, String actor, String comment, Instant expiresAt) {
        return requests.decidePending(id, status, actor, comment, expiresAt);
    }

    @Override public int cancelOwnRequest(long id, long userId) { return requests.cancelOwnPending(id, userId); }
    @Override public int cancelPendingForUser(long userId, String comment) { return requests.cancelPendingForUser(userId, comment); }
    @Override public int cancelPendingForRole(long roleId, String comment) { return requests.cancelPendingForRole(roleId, comment); }

    @Override
    public List<AccessRequestRecord> requestsOfUser(long userId, long applicationId) {
        return requests.findRecentByUserAndApplication(userId, applicationId, 50).stream()
                .map(JpaGovernanceRepositoryAdapter::toRequestRecord).toList();
    }

    @Override
    public long createReview(String name, long applicationId, Long departmentId, Instant dueAt, String actor) {
        return reviews.createReview(name, applications.getReferenceById(applicationId),
                departmentId == null ? null : departments.findById(departmentId).orElseThrow(), dueAt, actor);
    }

    @Override
    public int insertReviewSnapshot(long reviewId, long applicationId, Long departmentId) {
        return reviewItems.insertSnapshot(applicationId, departmentId,
                reviews.getReferenceById(reviewId), users::getReferenceById, roles::getReferenceById);
    }

    @Override
    public Optional<ReviewDetailRecord> reviewView(long id) {
        return reviews.findDetailById(id).map(r -> new ReviewDetailRecord(
                r.id(), r.name(), r.applicationId(), r.applicationName(), r.departmentId(), r.departmentName(),
                r.status(), r.dueAt(), r.createdBy(), r.createdAt(), r.closedBy(), r.closedAt(), r.summary(),
                r.items(), r.pending(), r.kept(), r.revoked(), r.skipped()));
    }

    @Override
    public Optional<AccessReviewAggregate> findReview(long id) {
        return reviews.findDetailById(id).map(row -> new AccessReviewAggregate(
                row.id(), row.name(), row.applicationId(), row.departmentId(), row.status()));
    }

    @Override
    public List<ReviewSummaryRecord> reviews(Long applicationId, DomainVisibility visibility, String status) {
        return reviews.findVisibleReviews(applicationId, toVisibility(visibility), status).stream()
                .map(r -> new ReviewSummaryRecord(
                        r.id(), r.name(), r.applicationId(), r.applicationName(), r.departmentId(), r.departmentName(),
                        r.status(), r.dueAt(), r.createdBy(), r.createdAt(), r.closedBy(), r.closedAt(),
                        r.items(), r.pending(), r.kept(), r.revoked(), r.skipped())).toList();
    }

    @Override
    public PageSlice<ReviewItemRecord> reviewItems(long reviewId, String decision, int page, int size) {
        var p = reviewItems.findItemsPage(reviewId, decision, page, size);
        return new PageSlice<>(p.getContent().stream().map(i -> new ReviewItemRecord(
                i.id(), i.userId(), i.userName(), i.externalId(), i.roleId(), i.roleName(), i.roleCode(),
                i.departmentId(), i.departmentName(), i.decision(), i.comment(), i.decidedBy(), i.decidedAt(),
                i.applied(), i.skipped())).toList(), p.getTotalElements());
    }

    @Override public Optional<String> lockReviewStatus(long reviewId) { return reviews.lockStatusById(reviewId); }

    @Override
    public int decideReviewItem(long itemId, long reviewId, String decision, String actor, String comment) {
        return reviewItems.decideItem(itemId, reviewId, decision, actor, comment);
    }

    @Override
    public List<AccessReviewAggregate.RevocationCandidate> pendingRevocations(long reviewId) {
        return reviewItems.findPendingRevocations(reviewId).stream()
                .map(row -> new AccessReviewAggregate.RevocationCandidate(
                        row.getId(), row.getUserId(), row.getRoleId(), row.getDepartmentId(), row.getGrantId()))
                .toList();
    }

    @Override public int revokeMatchingGrant(long userId, long roleId, String grantId) { return userRoles.revokeGrant(userId, roleId, grantId); }
    @Override public void markReviewItemApplied(long itemId, boolean skipped) { reviewItems.markApplied(itemId, skipped); }
    @Override public int closeReview(long reviewId, String actor, String summary) { return reviews.closeOpenReview(reviewId, actor, summary); }

    private static AccessRequestRecord toRequestRecord(AccessRequestRepository.AccessRequestRow r) {
        return new AccessRequestRecord(
                r.id(), r.userId(), r.userName(), r.externalId(), r.departmentId(), r.departmentName(),
                r.applicationId(), r.applicationName(), r.roleId(), r.roleName(), r.roleCode(), r.roleEnabled(),
                r.reason(), r.durationDays(), r.status(), r.requestedBy(), r.decidedBy(), r.decidedAt(),
                r.decisionComment(), r.grantExpiresAt(), r.createdAt());
    }
}
