package com.melonhub.rbac.api.governance;

import static com.melonhub.rbac.service.port.security.AdminFunction.DOMAIN_TRANSFER;
import static com.melonhub.rbac.service.port.security.AdminFunction.DOMAIN_VIEW;
import static com.melonhub.rbac.service.port.security.AdminFunction.GOVERNANCE_VIEW;
import static com.melonhub.rbac.service.port.security.AdminFunction.REQUEST_APPROVE;
import static com.melonhub.rbac.service.port.security.AdminFunction.REVIEW_MANAGE;

import com.melonhub.rbac.api.governance.dto.ManifestDTO;
import com.melonhub.rbac.api.governance.dto.RequestApproveDTO;
import com.melonhub.rbac.api.governance.dto.RequestRejectDTO;
import com.melonhub.rbac.api.governance.dto.ReviewCreateDTO;
import com.melonhub.rbac.api.governance.dto.ReviewItemDecisionDTO;
import com.melonhub.rbac.api.governance.vo.AccessRequestVO;
import com.melonhub.rbac.api.governance.vo.ExpiringAssignmentVO;
import com.melonhub.rbac.api.governance.vo.ManifestImportResultVO;
import com.melonhub.rbac.api.governance.vo.PermissionHoldersVO;
import com.melonhub.rbac.api.governance.vo.PermissionManifestVO;
import com.melonhub.rbac.api.governance.vo.ReviewDetailVO;
import com.melonhub.rbac.api.governance.vo.ReviewItemVO;
import com.melonhub.rbac.api.governance.vo.ReviewSummaryVO;
import com.melonhub.rbac.api.shared.vo.OkVO;
import com.melonhub.rbac.api.shared.vo.PageVO;
import com.melonhub.rbac.infrastructure.security.AdminAccess;
import com.melonhub.rbac.infrastructure.security.RequiresFunction;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.governance.GovernanceModels.AccessRequestResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ApproveRequestCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.CreateReviewCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.DecideReviewItemCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.RejectRequestCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ReviewDetailResult;
import com.melonhub.rbac.service.port.governance.GovernanceUseCase;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台授权治理接口：角色申请审批、定期权限复核、权限清单、使用者反查与即将到期报表。
 */
@RestController
@RequestMapping("/api/admin")
public class GovernanceAdminController {
    private final GovernanceUseCase governance;

    public GovernanceAdminController(GovernanceUseCase governance) {
        this.governance = governance;
    }

    @RequiresFunction(GOVERNANCE_VIEW)
    @GetMapping("/access-requests")
    public PageVO<AccessRequestVO> requests(@RequestParam(required = false) Long applicationId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            HttpServletRequest req) {
        AdminPrincipal principal = AdminAccess.of(req);
        if (applicationId != null) {
            AdminAccess.application(req, applicationId);
        }
        return GovernanceVoMapper.toAccessRequestPageVO(governance.requests(applicationId, principal.visibility(),
                status, keyword, Math.max(1, page), Math.min(200, Math.max(1, size))));
    }

    @RequiresFunction(REQUEST_APPROVE)
    @PostMapping("/access-requests/{id}/approve")
    public AccessRequestVO approve(@PathVariable long id, @Valid @RequestBody RequestApproveDTO body,
                                   HttpServletRequest req) {
        AccessRequestResult found = governance.request(id);
        AdminAccess.domain(req, found.departmentId(), found.applicationId());
        return GovernanceVoMapper.toAccessRequestVO(governance.approve(id,
                new ApproveRequestCommand(body.durationDays(), body.comment(), body.permanent()),
                AdminAccess.of(req).actor()));
    }

    @RequiresFunction(REQUEST_APPROVE)
    @PostMapping("/access-requests/{id}/reject")
    public AccessRequestVO reject(@PathVariable long id, @Valid @RequestBody RequestRejectDTO body,
                                  HttpServletRequest req) {
        AccessRequestResult found = governance.request(id);
        AdminAccess.domain(req, found.departmentId(), found.applicationId());
        return GovernanceVoMapper.toAccessRequestVO(governance.reject(id,
                new RejectRequestCommand(body.comment()), AdminAccess.of(req).actor()));
    }

    @RequiresFunction(GOVERNANCE_VIEW)
    @GetMapping("/reviews")
    public List<ReviewSummaryVO> reviews(@RequestParam(required = false) Long applicationId,
                                         @RequestParam(required = false) String status,
                                         HttpServletRequest req) {
        AdminPrincipal principal = AdminAccess.of(req);
        if (applicationId != null) {
            AdminAccess.application(req, applicationId);
        }
        return governance.reviews(applicationId, principal.visibility(), status).stream()
                .map(GovernanceVoMapper::toReviewSummaryVO).toList();
    }

    @RequiresFunction(REVIEW_MANAGE)
    @PostMapping("/reviews")
    public ReviewDetailVO createReview(@Valid @RequestBody ReviewCreateDTO body, HttpServletRequest req) {
        String actor = AdminAccess.domain(req, body.departmentId(), body.applicationId()).actor();
        return GovernanceVoMapper.toReviewDetailVO(governance.createReview(
                new CreateReviewCommand(body.name(), body.applicationId(), body.departmentId(), body.dueDays()), actor));
    }

    @RequiresFunction(GOVERNANCE_VIEW)
    @GetMapping("/reviews/{id}")
    public ReviewDetailVO review(@PathVariable long id, HttpServletRequest req) {
        ReviewDetailResult found = governance.review(id);
        AdminAccess.domain(req, found.departmentId(), found.applicationId());
        return GovernanceVoMapper.toReviewDetailVO(found);
    }

    @RequiresFunction(GOVERNANCE_VIEW)
    @GetMapping("/reviews/{id}/items")
    public PageVO<ReviewItemVO> reviewItems(@PathVariable long id,
                                            @RequestParam(required = false) String decision,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            HttpServletRequest req) {
        ReviewDetailResult found = governance.review(id);
        AdminAccess.domain(req, found.departmentId(), found.applicationId());
        return GovernanceVoMapper.toReviewItemPageVO(governance.reviewItems(id, decision,
                Math.max(1, page), Math.min(200, Math.max(1, size))));
    }

    @RequiresFunction(REVIEW_MANAGE)
    @PutMapping("/reviews/{id}/items/{itemId}")
    public OkVO decideItem(@PathVariable long id, @PathVariable long itemId,
                           @Valid @RequestBody ReviewItemDecisionDTO body, HttpServletRequest req) {
        ReviewDetailResult found = governance.review(id);
        AdminAccess.domain(req, found.departmentId(), found.applicationId());
        governance.decideReviewItem(id, itemId, new DecideReviewItemCommand(body.decision(), body.comment()),
                AdminAccess.of(req).actor());
        return OkVO.OK;
    }

    @RequiresFunction(REVIEW_MANAGE)
    @PostMapping("/reviews/{id}/close")
    public ReviewDetailVO closeReview(@PathVariable long id, HttpServletRequest req) {
        ReviewDetailResult found = governance.review(id);
        AdminAccess.domain(req, found.departmentId(), found.applicationId());
        return GovernanceVoMapper.toReviewDetailVO(governance.closeReview(id, AdminAccess.of(req).actor()));
    }

    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/manifest")
    public PermissionManifestVO exportManifest(@RequestParam long applicationId,
                                               @RequestParam(required = false) Long departmentId,
                                               HttpServletRequest req) {
        AdminPrincipal principal = departmentId == null
                ? AdminAccess.application(req, applicationId)
                : AdminAccess.domain(req, departmentId, applicationId);
        return GovernanceVoMapper.toManifestVO(governance.exportManifest(applicationId, departmentId,
                principal.visibleDepartments()));
    }

    @RequiresFunction(DOMAIN_TRANSFER)
    @PostMapping("/manifest/import")
    public ManifestImportResultVO importManifest(@RequestParam long applicationId,
                                                 @Valid @RequestBody ManifestDTO body,
                                                 HttpServletRequest req) {
        AdminPrincipal principal = AdminAccess.application(req, applicationId);
        return GovernanceVoMapper.toManifestImportResultVO(governance.importManifest(applicationId,
                GovernanceVoMapper.toManifestCommand(body), principal.actor(), principal.visibleDepartments()));
    }

    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/permissions/who")
    public PermissionHoldersVO permissionHolders(@RequestParam long applicationId,
                                                 @RequestParam String code,
                                                 @RequestParam(required = false) Long departmentId,
                                                 HttpServletRequest req) {
        AdminPrincipal principal = departmentId == null
                ? AdminAccess.application(req, applicationId)
                : AdminAccess.domain(req, departmentId, applicationId);
        return GovernanceVoMapper.toPermissionHoldersVO(governance.permissionHolders(applicationId, code,
                departmentId, principal.visibleDepartments()));
    }

    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/assignments/expiring")
    public List<ExpiringAssignmentVO> expiring(@RequestParam(defaultValue = "7") int days,
                                               @RequestParam(required = false) Long applicationId,
                                               HttpServletRequest req) {
        AdminPrincipal principal = AdminAccess.of(req);
        if (days < 1 || days > 365) {
            throw ApiException.bad("天数需在 1-365 之间");
        }
        if (applicationId != null) {
            AdminAccess.application(req, applicationId);
        }
        return governance.expiringAssignments(days, principal.visibility(), applicationId).stream()
                .map(GovernanceVoMapper::toExpiringAssignmentVO).toList();
    }
}
