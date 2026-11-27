package com.melonhub.rbac.service.port.governance;

import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.AccessRequestResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ApproveRequestCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.CreateReviewCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.DecideReviewItemCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ExpiringAssignmentResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestImportCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestImportResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.PermissionHoldersResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.PermissionManifestResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.RejectRequestCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ReviewDetailResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ReviewItemResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ReviewSummaryResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.SelfAccessResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.SubmitAccessRequestCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.UserImportCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.UserImportResult;
import com.melonhub.rbac.service.port.security.Visibility;
import java.util.Collection;
import java.util.List;

/**
 * 授权治理用例端口：角色申请审批、定期权限复核、到期清理、批量导入、清单同步与反查报表。
 */
public interface GovernanceUseCase {
    int HOLDER_LIMIT = 500;

    PageResult<AccessRequestResult> requests(Long app, Visibility visible, String status, String keyword, int page, int size);

    AccessRequestResult request(long id);

    AccessRequestResult submit(long app, SubmitAccessRequestCommand body, String actor);

    AccessRequestResult approve(long id, ApproveRequestCommand body, String actor);

    AccessRequestResult reject(long id, RejectRequestCommand body, String actor);

    void cancelOwn(long app, long id, String externalId, String actor);

    List<AccessRequestResult> myRequests(long app, String externalId);

    SelfAccessResult myAccess(long app, String externalId, long dept);

    ReviewDetailResult createReview(CreateReviewCommand body, String actor);

    ReviewDetailResult review(long id);

    List<ReviewSummaryResult> reviews(Long app, Visibility visible, String status);

    PageResult<ReviewItemResult> reviewItems(long review, String decision, int page, int size);

    void decideReviewItem(long reviewId, long itemId, DecideReviewItemCommand body, String actor);

    ReviewDetailResult closeReview(long reviewId, String actor);

    int expireAssignments(int limit);

    UserImportResult importUsers(UserImportCommand body, String actor);

    PermissionManifestResult exportManifest(long app, Long departmentFilter, Collection<Long> visibleDepartments);

    ManifestImportResult importManifest(long app, ManifestImportCommand manifest, String actor, Collection<Long> scope);

    PermissionHoldersResult permissionHolders(long app, String code, Long department, Collection<Long> visibleDepartments);

    List<ExpiringAssignmentResult> expiringAssignments(int days, Visibility visible, Long app);
}
