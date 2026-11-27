package com.melonhub.rbac.service.structured.governance;

import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.persistence.entity.Permission;
import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessRequestRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessReviewItemRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessReviewRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.MembershipRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionContextRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleInheritanceRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RolePermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRoleRepository;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacUseCase;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.governance.GovernanceModels.AccessRequestResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ApproveRequestCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.CreateReviewCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.DecideReviewItemCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ExpiringAssignmentResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestApplicationResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestDepartmentCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestDepartmentResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestImportCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestImportResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestPermissionCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestPermissionResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestRoleCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ManifestRoleResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.PermissionHolderResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.PermissionHolderRoleResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.PermissionHoldersResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.PermissionManifestResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.RejectRequestCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ReviewDetailResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ReviewItemResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.ReviewSummaryResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.SelfAccessResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.SelfAccessRoleResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.SubmitAccessRequestCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.UserImportCommand;
import com.melonhub.rbac.service.port.governance.GovernanceModels.UserImportFailureResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.UserImportResult;
import com.melonhub.rbac.service.port.governance.GovernanceModels.UserImportRowCommand;
import com.melonhub.rbac.service.port.governance.GovernanceUseCase;
import com.melonhub.rbac.service.port.organization.OrganizationModels.DepartmentResult;
import com.melonhub.rbac.service.port.organization.OrganizationUseCase;
import com.melonhub.rbac.service.port.security.Visibility;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Structured 授权治理服务：角色申请审批、定期权限复核、分配到期清理、批量导入、清单同步与权限反查。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "structured", matchIfMissing = true)
public class StructuredGovernanceService implements GovernanceUseCase {
    private record UserDomainKey(long userId, long departmentId, long applicationId) {}
    private record HolderKey(long userId, long departmentId) {}

    private final AccessRequestRepository accessRequests;
    private final AccessReviewRepository accessReviews;
    private final AccessReviewItemRepository reviewItems;
    private final DepartmentRepository departments;
    private final ApplicationRepository applications;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final RolePermissionRepository rolePermissions;
    private final RoleInheritanceRepository roleInheritances;
    private final UserRoleRepository userRoles;
    private final PermissionContextRepository contexts;
    private final PermissionCache cache;
    private final OrganizationUseCase organization;
    private final DomainRbacUseCase domainRbac;
    private final AuditUseCase audit;
    private final WebhookUseCase webhooks;
    private final TransactionTemplate rowTx;

    public StructuredGovernanceService(AccessRequestRepository accessRequests, AccessReviewRepository accessReviews,
                                       AccessReviewItemRepository reviewItems, DepartmentRepository departments,
                                       ApplicationRepository applications, UserRepository users,
                                       MembershipRepository memberships, RoleRepository roles,
                                       PermissionRepository permissions, RolePermissionRepository rolePermissions,
                                       RoleInheritanceRepository roleInheritances, UserRoleRepository userRoles,
                                       PermissionContextRepository contexts, PermissionCache cache,
                                       OrganizationUseCase organization, DomainRbacUseCase domainRbac,
                                       AuditUseCase audit, WebhookUseCase webhooks,
                                       PlatformTransactionManager transactions) {
        this.accessRequests = accessRequests;
        this.accessReviews = accessReviews;
        this.reviewItems = reviewItems;
        this.departments = departments;
        this.applications = applications;
        this.users = users;
        this.memberships = memberships;
        this.roles = roles;
        this.permissions = permissions;
        this.rolePermissions = rolePermissions;
        this.roleInheritances = roleInheritances;
        this.userRoles = userRoles;
        this.contexts = contexts;
        this.cache = cache;
        this.organization = organization;
        this.domainRbac = domainRbac;
        this.audit = audit;
        this.webhooks = webhooks;
        this.rowTx = new TransactionTemplate(transactions);
    }

    @Override
    public PageResult<AccessRequestResult> requests(Long app, Visibility visible, String status, String keyword, int page, int size) {
        if (status != null && !status.isBlank() && !List.of("PENDING", "APPROVED", "REJECTED", "CANCELLED").contains(status.strip())) {
            throw ApiException.bad("状态只能是 PENDING、APPROVED、REJECTED 或 CANCELLED");
        }
        Page<AccessRequestRepository.AccessRequestRow> p = accessRequests.findPage(app, visible, status, keyword, page, size);
        return PageResult.of(p.getContent().stream().map(StructuredGovernanceService::requestResult).toList(),
                p.getTotalElements(), page, size);
    }

    @Override
    public AccessRequestResult request(long id) {
        return accessRequests.findDetailById(id).map(StructuredGovernanceService::requestResult)
                .orElseThrow(() -> ApiException.missing("申请不存在"));
    }

    @Override
    @Transactional
    public AccessRequestResult submit(long app, SubmitAccessRequestCommand body, String actor) {
        long user = users.lockIdByExternalId(body.externalId()).orElseThrow(() -> ApiException.missing("用户不存在"));
        if (!memberships.existsLocked(user, body.departmentId())) {
            throw ApiException.forbidden("用户不属于目标部门");
        }
        if (!roles.existsByIdAndDepartmentIdAndApplicationId(body.roleId(), body.departmentId(), app)) {
            throw ApiException.missing("角色不存在于该隔离域");
        }
        if (accessRequests.existsPending(user, body.roleId())) {
            throw ApiException.conflict("该用户已有此角色的待审批申请");
        }
        long id;
        try {
            id = accessRequests.createRequest(
                    users.findById(user).orElseThrow(),
                    departments.findById(body.departmentId()).orElseThrow(),
                    applications.getReferenceById(app),
                    roles.findById(body.roleId()).orElseThrow(),
                    body.reason().strip(), body.durationDays(), actor);
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateKey(e)) {
                throw ApiException.conflict("该用户已有此角色的待审批申请");
            }
            throw e;
        }
        audit.audit(actor, "ACCESS_REQUESTED", body.externalId(), body.departmentId(), app, "角色 #" + body.roleId());
        return request(id);
    }

    @Override
    @Transactional
    public AccessRequestResult approve(long id, ApproveRequestCommand body, String actor) {
        AccessRequestResult req = request(id);
        if (!"PENDING".equals(req.status())) {
            throw ApiException.conflict("申请已被处理");
        }
        if (req.userId() == null || req.roleId() == null || req.departmentId() == null) {
            throw ApiException.conflict("申请涉及的用户、角色或部门已删除");
        }
        long user = req.userId();
        long dept = req.departmentId();
        long app = req.applicationId();
        long role = req.roleId();
        if (!Boolean.TRUE.equals(req.roleEnabled())) {
            throw ApiException.conflict("角色已停用，无法授权");
        }
        boolean permanent = Boolean.TRUE.equals(body.permanent());
        if (permanent && body.durationDays() != null) {
            throw ApiException.bad("永久授权时不能同时指定天数");
        }
        Integer days = permanent ? null : body.durationDays() != null ? body.durationDays() : req.durationDays();
        Instant expires = days == null ? null : Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(days, ChronoUnit.DAYS);
        users.lockExternalIdById(user).orElseThrow(() -> ApiException.missing("用户不存在"));
        if (!memberships.existsLocked(user, dept)) {
            throw ApiException.conflict("用户已不属于该部门，无法授权");
        }
        userRoles.grantRole(user, dept, app, role, expires, actor, users::getReferenceById, roles::getReferenceById);
        if (accessRequests.decidePending(id, "APPROVED", actor, text(body.comment()), expires) == 0) {
            throw ApiException.conflict("申请已被并发处理");
        }
        contexts.revokeDomain(user, dept, app);
        cache.touchUser(user);
        webhooks.emit(app, "access.approved", Map.of("userId", user, "externalId", req.externalId(), "departmentId", dept, "roleId", role));
        audit.audit(actor, "ACCESS_REQUEST_APPROVED", req.externalId(), dept, app,
                "角色 " + req.roleCode() + (days == null ? "，永久授权" : "，" + days + " 天有效"));
        return request(id);
    }

    @Override
    @Transactional
    public AccessRequestResult reject(long id, RejectRequestCommand body, String actor) {
        AccessRequestResult req = request(id);
        if (!"PENDING".equals(req.status())) {
            throw ApiException.conflict("申请已被处理");
        }
        if (accessRequests.decidePending(id, "REJECTED", actor, text(body.comment()), null) == 0) {
            throw ApiException.conflict("申请已被并发处理");
        }
        long app = req.applicationId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", req.userId());
        data.put("externalId", req.externalId());
        data.put("departmentId", req.departmentId());
        data.put("roleId", req.roleId());
        data.put("comment", text(body.comment()));
        webhooks.emit(app, "access.rejected", data);
        audit.audit(actor, "ACCESS_REQUEST_REJECTED", req.externalId(), req.departmentId(), app,
                "角色 " + req.roleCode() + (body.comment() == null || body.comment().isBlank() ? "" : "，理由：" + body.comment()));
        return request(id);
    }

    @Override
    @Transactional
    public void cancelOwn(long app, long id, String externalId, String actor) {
        long user = users.findIdByExternalId(externalId).orElseThrow(() -> ApiException.missing("用户不存在"));
        AccessRequestResult req = request(id);
        if (req.applicationId() != app || !Long.valueOf(user).equals(req.userId())) {
            throw ApiException.missing("申请不存在");
        }
        if (accessRequests.cancelOwnPending(id, user) == 0) {
            throw ApiException.conflict("申请已被处理，无法撤回");
        }
        audit.audit(actor, "ACCESS_REQUEST_CANCELLED", externalId, req.departmentId(), app, "角色 " + req.roleCode());
    }

    @Override
    public List<AccessRequestResult> myRequests(long app, String externalId) {
        long user = users.findIdByExternalId(externalId).orElseThrow(() -> ApiException.missing("用户不存在"));
        return accessRequests.findRecentByUserAndApplication(user, app, 50).stream()
                .map(StructuredGovernanceService::requestResult).toList();
    }

    @Override
    public SelfAccessResult myAccess(long app, String externalId, long dept) {
        long user = users.findIdByExternalId(externalId).orElseThrow(() -> ApiException.missing("用户不存在"));
        domainRbac.domain(dept, app);
        if (!memberships.existsByUserIdAndDepartmentId(user, dept)) {
            throw ApiException.forbidden("用户不属于目标部门");
        }
        Instant now = Instant.now();
        List<Long> usable = new ArrayList<>();
        List<SelfAccessRoleResult> view = new ArrayList<>();
        for (UserRoleRepository.DomainUserRole role : userRoles.findUserRolesInDomain(user, dept, app)) {
            boolean expired = role.getExpiresAt() != null && role.getExpiresAt().isBefore(now);
            if (role.isEnabled() && !expired) {
                usable.add(role.getId());
            }
            view.add(new SelfAccessRoleResult(role.getId(), role.getName(), role.getCode(), role.isEnabled(), role.getExpiresAt()));
        }
        List<Long> expanded = roleInheritances.expandActiveRoleIds(usable, dept, app);
        Set<String> grantedCodes = new LinkedHashSet<>();
        if (!expanded.isEmpty()) {
            for (RolePermissionRepository.GrantProjection grant : rolePermissions.findGrantsByRoleIds(expanded)) {
                grantedCodes.add(grant.getCode());
            }
        }
        return new SelfAccessResult(user, dept, app, view, new ArrayList<>(grantedCodes));
    }

    @Override
    @Transactional
    public ReviewDetailResult createReview(CreateReviewCommand body, String actor) {
        if (!applications.existsById(body.applicationId())) {
            throw ApiException.missing("软件不存在");
        }
        if (body.departmentId() != null && !departments.existsById(body.departmentId())) {
            throw ApiException.missing("部门不存在");
        }
        Instant due = body.dueDays() == null ? null : Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(body.dueDays(), ChronoUnit.DAYS);
        long id = accessReviews.createReview(body.name().strip(), applications.getReferenceById(body.applicationId()),
                body.departmentId() == null ? null : departments.findById(body.departmentId()).orElseThrow(),
                due, actor);
        int items = reviewItems.insertSnapshot(body.applicationId(), body.departmentId(),
                accessReviews.getReferenceById(id), users::getReferenceById, roles::getReferenceById);
        audit.audit(actor, "REVIEW_CREATED", body.name(), body.departmentId(), body.applicationId(), "快照包含 " + items + " 条授权");
        return review(id);
    }

    @Override
    public ReviewDetailResult review(long id) {
        return accessReviews.findDetailById(id).map(r -> new ReviewDetailResult(
                        r.id(), r.name(), r.applicationId(), r.applicationName(), r.departmentId(), r.departmentName(),
                        r.status(), r.dueAt(), r.createdBy(), r.createdAt(), r.closedBy(), r.closedAt(), r.summary(),
                        r.items(), r.pending(), r.kept(), r.revoked(), r.skipped()))
                .orElseThrow(() -> ApiException.missing("复核不存在"));
    }

    @Override
    public List<ReviewSummaryResult> reviews(Long app, Visibility visible, String status) {
        if (status != null && !status.isBlank() && !List.of("OPEN", "CLOSED").contains(status.strip())) {
            throw ApiException.bad("状态只能是 OPEN 或 CLOSED");
        }
        return accessReviews.findVisibleReviews(app, visible, status).stream()
                .map(r -> new ReviewSummaryResult(
                        r.id(), r.name(), r.applicationId(), r.applicationName(), r.departmentId(), r.departmentName(),
                        r.status(), r.dueAt(), r.createdBy(), r.createdAt(), r.closedBy(), r.closedAt(),
                        r.items(), r.pending(), r.kept(), r.revoked(), r.skipped()))
                .toList();
    }

    @Override
    public PageResult<ReviewItemResult> reviewItems(long review, String decision, int page, int size) {
        review(review);
        if (decision != null && !decision.isBlank() && !List.of("PENDING", "KEEP", "REVOKE").contains(decision.strip())) {
            throw ApiException.bad("决定只能是 PENDING、KEEP 或 REVOKE");
        }
        Page<AccessReviewItemRepository.ReviewItemRow> p = reviewItems.findItemsPage(review, decision, page, size);
        return PageResult.of(p.getContent().stream().map(i -> new ReviewItemResult(
                        i.id(), i.userId(), i.userName(), i.externalId(), i.roleId(), i.roleName(), i.roleCode(),
                        i.departmentId(), i.departmentName(), i.decision(), i.comment(), i.decidedBy(), i.decidedAt(),
                        i.applied(), i.skipped())).toList(),
                p.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public void decideReviewItem(long reviewId, long itemId, DecideReviewItemCommand body, String actor) {
        lockOpenReview(reviewId);
        if (reviewItems.decideItem(itemId, reviewId, body.decision(), actor, text(body.comment())) == 0) {
            throw ApiException.missing("复核条目不存在");
        }
    }

    private void lockOpenReview(long reviewId) {
        String status = accessReviews.lockStatusById(reviewId).orElseThrow(() -> ApiException.missing("复核不存在"));
        if (!"OPEN".equals(status)) {
            throw ApiException.conflict("复核已关闭");
        }
    }

    @Override
    @Transactional
    public ReviewDetailResult closeReview(long reviewId, String actor) {
        lockOpenReview(reviewId);
        ReviewDetailResult review = review(reviewId);
        long app = review.applicationId();
        List<AccessReviewItemRepository.RevocationCandidate> revocations = reviewItems.findPendingRevocations(reviewId);
        Set<Long> userIds = new TreeSet<>();
        for (AccessReviewItemRepository.RevocationCandidate item : revocations) {
            if (item.getUserId() != null) {
                userIds.add(item.getUserId());
            }
        }
        users.lockIdsByIdIn(userIds);
        Map<Long, List<Long>> domains = new LinkedHashMap<>();
        Set<Long> touched = new LinkedHashSet<>();
        int revoked = 0;
        int skipped = 0;
        for (AccessReviewItemRepository.RevocationCandidate item : revocations) {
            boolean applied = item.getUserId() != null && item.getRoleId() != null
                    && userRoles.revokeGrant(item.getUserId(), item.getRoleId(), item.getGrantId()) > 0;
            reviewItems.markApplied(item.getId(), !applied);
            if (!applied) {
                skipped++;
                continue;
            }
            long user = item.getUserId();
            revoked++;
            touched.add(user);
            domains.computeIfAbsent(item.getDepartmentId(), k -> new ArrayList<>()).add(user);
        }
        for (Map.Entry<Long, List<Long>> entry : domains.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                contexts.revokeDomainForUsers(entry.getValue(), entry.getKey(), app);
            }
        }
        for (long user : touched) {
            cache.touchUser(user);
        }
        String summary = "回收 " + revoked + " 条授权"
                + (skipped == 0 ? "" : "，" + skipped + " 条在复核期间已变更或移除，未回收") + "，未决条目视为保留";
        if (accessReviews.closeOpenReview(reviewId, actor, summary) == 0) {
            throw ApiException.conflict("复核已被并发关闭");
        }
        if (revoked > 0) {
            webhooks.emit(app, "review.closed", Map.of("reviewId", reviewId, "revoked", revoked));
        }
        audit.audit(actor, "REVIEW_CLOSED", review.name(), review.departmentId(), app, summary);
        return review(reviewId);
    }

    @Override
    @Transactional
    public int expireAssignments(int limit) {
        Map<UserDomainKey, List<UserRoleRepository.LapsedAssignment>> lapsed = new LinkedHashMap<>();
        for (UserRoleRepository.LapsedAssignment row : userRoles.findLapsedAssignments(Limit.of(limit))) {
            if (userRoles.deleteLapsedAssignment(row.getUserId(), row.getRoleId()) == 0) {
                continue;
            }
            UserDomainKey key = new UserDomainKey(row.getUserId(), row.getDepartmentId(), row.getApplicationId());
            lapsed.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        int expired = 0;
        for (List<UserRoleRepository.LapsedAssignment> rows : lapsed.values()) {
            UserRoleRepository.LapsedAssignment first = rows.get(0);
            long user = first.getUserId();
            long dept = first.getDepartmentId();
            long app = first.getApplicationId();
            String external = first.getExternalId();
            cache.touchUser(user);
            webhooks.emit(app, "user.roles.expired", Map.of("userId", user, "externalId", external, "departmentId", dept,
                    "roleIds", rows.stream().map(UserRoleRepository.LapsedAssignment::getRoleId).toList()));
            audit.audit("system", "USER_ROLES_EXPIRED", external, dept, app,
                    "到期角色 " + rows.stream().map(UserRoleRepository.LapsedAssignment::getRoleCode).collect(Collectors.joining("、")));
            expired += rows.size();
        }
        return expired;
    }

    @Override
    public UserImportResult importUsers(UserImportCommand body, String actor) {
        Set<String> codes = new LinkedHashSet<>();
        for (UserImportRowCommand row : body.users()) {
            if (row != null && row.departmentCodes() != null) {
                codes.addAll(row.departmentCodes());
            }
        }
        codes.removeIf(Objects::isNull);
        Map<String, Long> departmentByCode = new HashMap<>();
        if (!codes.isEmpty()) {
            for (DepartmentRepository.CodeId row : departments.findCodeIdsByCodeIn(codes)) {
                departmentByCode.put(row.getCode(), row.getId());
            }
        }
        int created = 0;
        int updated = 0;
        List<UserImportFailureResult> failures = new ArrayList<>();
        int line = 0;
        for (UserImportRowCommand row : body.users()) {
            line++;
            try {
                validateImportRow(row);
                Set<Long> wanted = new LinkedHashSet<>();
                for (String code : row.departmentCodes()) {
                    Long dept = departmentByCode.get(code);
                    if (dept == null) {
                        throw ApiException.missing("部门编码不存在: " + code);
                    }
                    wanted.add(dept);
                }
                String email = row.email() == null ? "" : row.email().strip();
                boolean createdRow = Boolean.TRUE.equals(rowTx.execute(status -> {
                    Optional<Long> existing = users.lockIdByExternalId(row.externalId());
                    long userId;
                    boolean isNew;
                    if (existing.isPresent()) {
                        userId = existing.get();
                        users.updateProfile(userId, row.name().strip(), email);
                        isNew = false;
                    } else {
                        userId = users.saveAndFlush(new User()
                                .setName(row.name().strip())
                                .setExternalId(row.externalId())
                                .setEmail(email)
                                .setEnabled(true)).getId();
                        isNew = true;
                    }
                    Set<Long> current = new HashSet<>(memberships.findLockedDepartmentIds(userId));
                    for (long dept : wanted) {
                        if (!current.contains(dept)) {
                            memberships.addIfAbsent(userId, dept, users::getReferenceById, departments::getReferenceById);
                        }
                    }
                    return isNew;
                }));
                if (createdRow) {
                    created++;
                } else {
                    updated++;
                }
            } catch (ApiException | DataIntegrityViolationException e) {
                failures.add(new UserImportFailureResult(
                        line,
                        row == null ? null : row.externalId(),
                        e instanceof ApiException ? e.getMessage() : "外部标识已存在或关联数据冲突"));
            }
        }
        audit.audit(actor, "USER_IMPORTED", "users", null, null,
                "新建 " + created + "，更新 " + updated + "，失败 " + failures.size());
        return new UserImportResult(created, updated, failures);
    }

    private static boolean isDuplicateKey(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                return true;
            }
        }
        return false;
    }

    private static void validateImportRow(UserImportRowCommand row) {
        if (row == null) {
            throw ApiException.bad("该行为空");
        }
        Set<String> fields = new TreeSet<>();
        if (row.name() == null || row.name().isBlank() || row.name().length() > 100) {
            fields.add("name");
        }
        if (row.externalId() == null || row.externalId().isBlank() || row.externalId().length() > 100) {
            fields.add("externalId");
        }
        if (row.email() != null && row.email().length() > 150) {
            fields.add("email");
        }
        if (row.departmentCodes() == null || row.departmentCodes().size() > 100
                || row.departmentCodes().stream().anyMatch(c -> c == null || c.isBlank() || c.length() > 64)) {
            fields.add("departmentCodes");
        }
        if (!fields.isEmpty()) {
            throw ApiException.bad("字段 " + String.join("、", fields) + " 缺失或格式不正确");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PermissionManifestResult exportManifest(long app, Long departmentFilter, Collection<Long> visibleDepartments) {
        ApplicationRepository.Ref application = applications.findRefById(app)
                .orElseThrow(() -> ApiException.missing("软件不存在"));
        Map<Long, DepartmentResult> departmentsById = new HashMap<>();
        for (DepartmentResult department : organization.departments(null)) {
            departmentsById.put(department.id(), department);
        }
        List<Long> departmentIds;
        if (departmentFilter != null) {
            departmentIds = List.of(departmentFilter);
        } else {
            Set<Long> manifestDepts = new TreeSet<>(permissions.findDistinctDepartmentIdsByApplicationId(app));
            manifestDepts.addAll(roles.findDistinctDepartmentIdsByApplicationId(app));
            departmentIds = manifestDepts.stream()
                    .filter(id -> visibleDepartments == null || visibleDepartments.contains(id)).toList();
        }
        List<ManifestDepartmentResult> sections = new ArrayList<>();
        for (long dept : departmentIds) {
            DepartmentResult department = departmentsById.get(dept);
            if (department == null) {
                continue;
            }
            List<ManifestPermissionResult> perms = new ArrayList<>();
            Map<Long, String> codesById = new HashMap<>();
            for (PermissionRepository.PermissionSummary p : permissions.findSummariesByDomain(dept, app)) {
                codesById.put(p.getId(), p.getCode());
                perms.add(new ManifestPermissionResult(p.getCode(), p.getName(), p.getResource(), p.getAction()));
            }
            Map<Long, List<String>> grantsByRole = new HashMap<>();
            for (RolePermissionRepository.RolePermissionPair pair : rolePermissions.findPairsByDomain(dept, app)) {
                String code = codesById.get(pair.getPermissionId());
                if (code != null) {
                    grantsByRole.computeIfAbsent(pair.getRoleId(), k -> new ArrayList<>()).add(code);
                }
            }
            List<ManifestRoleResult> roleViews = new ArrayList<>();
            for (RoleRepository.RoleSummary r : roles.findSummariesByDomain(dept, app)) {
                roleViews.add(new ManifestRoleResult(r.getCode(), r.getName(), r.getDescription(), r.isEnabled(),
                        grantsByRole.getOrDefault(r.getId(), List.of())));
            }
            sections.add(new ManifestDepartmentResult(department.code(), department.name(), perms, roleViews));
        }
        return new PermissionManifestResult(1, new ManifestApplicationResult(application.getCode(), application.getName()),
                Instant.now().toString(), sections);
    }

    @Override
    @Transactional
    public ManifestImportResult importManifest(long app, ManifestImportCommand manifest, String actor, Collection<Long> scope) {
        if (!applications.existsById(app)) {
            throw ApiException.missing("软件不存在");
        }
        List<String> codes = manifest.departments().stream().map(ManifestDepartmentCommand::code).toList();
        Map<String, Long> departmentByCode = new HashMap<>();
        if (!codes.isEmpty()) {
            for (DepartmentRepository.CodeId row : departments.findCodeIdsByCodeIn(codes)) {
                departmentByCode.put(row.getCode(), row.getId());
            }
        }
        for (String code : codes) {
            if (!departmentByCode.containsKey(code)) {
                throw ApiException.missing("部门编码不存在: " + code);
            }
        }
        if (scope != null && !scope.containsAll(departmentByCode.values())) {
            throw ApiException.forbidden("清单中包含你管理范围以外的部门");
        }
        int permissionsCreated = 0;
        int permissionsUpdated = 0;
        int rolesCreated = 0;
        int rolesUpdated = 0;
        for (ManifestDepartmentCommand section : manifest.departments()) {
            long dept = departmentByCode.get(section.code());
            Map<Long, Boolean> wasEnabled = new HashMap<>();
            for (RoleRepository.RoleSummary role : roles.findSummariesByDomain(dept, app)) {
                wasEnabled.put(role.getId(), role.isEnabled());
            }
            Map<String, Long> permissionIds = new HashMap<>();
            for (ManifestPermissionCommand p : section.permissions()) {
                Optional<Long> existing = permissions.findIdByScopedCode(dept, app, p.code());
                if (existing.isPresent()) {
                    permissions.updateProfile(existing.get(), p.name(), p.code(), p.resource(), p.action());
                    permissionIds.put(p.code(), existing.get());
                    permissionsUpdated++;
                } else {
                    long createdPermId = permissions.saveAndFlush(new Permission()
                            .setDepartment(departments.getReferenceById(dept))
                            .setApplication(applications.getReferenceById(app))
                            .setName(p.name()).setCode(p.code()).setResource(p.resource()).setAction(p.action())).getId();
                    permissionIds.put(p.code(), createdPermId);
                    permissionsCreated++;
                }
            }
            for (ManifestRoleCommand r : section.roles()) {
                Set<Long> grants = new LinkedHashSet<>();
                for (String code : r.permissions()) {
                    Long permissionId = permissionIds.get(code);
                    if (permissionId == null) {
                        permissionId = permissions.findIdByScopedCode(dept, app, code)
                                .orElseThrow(() -> ApiException.bad("角色 " + r.code() + " 引用了不存在的权限编码 " + code));
                    }
                    grants.add(permissionId);
                }
                boolean enabled = r.enabled() == null || r.enabled();
                Long roleId = roles.findIdByScopedCode(dept, app, r.code()).orElse(null);
                if (roleId == null) {
                    long createdRoleId = roles.saveAndFlush(new Role()
                            .setDepartment(departments.getReferenceById(dept))
                            .setApplication(applications.getReferenceById(app))
                            .setName(r.name()).setCode(r.code()).setDescription(text(r.description())).setEnabled(enabled)).getId();
                    rolePermissions.replaceRolePermissions(createdRoleId, dept, app, grants,
                            roles::getReferenceById, permissions::getReferenceById);
                    rolesCreated++;
                } else {
                    Set<Long> before = new HashSet<>(rolePermissions.findPermissionIdsByRoleId(roleId));
                    roles.updateProfile(roleId, r.name(), r.code(), text(r.description()));
                    roles.updateStatus(roleId, enabled);
                    rolePermissions.replaceRolePermissions(roleId, dept, app, grants,
                            roles::getReferenceById, permissions::getReferenceById);
                    if (enabled != Boolean.TRUE.equals(wasEnabled.get(roleId))) {
                        webhooks.emit(app, enabled ? "role.enabled" : "role.disabled", Map.of("roleId", roleId, "departmentId", dept));
                    }
                    if (!before.equals(grants)) {
                        webhooks.emit(app, "role.permissions.changed", Map.of("roleId", roleId, "departmentId", dept));
                    }
                    rolesUpdated++;
                }
            }
            cache.touchDomain(dept, app);
        }
        audit.audit(actor, "MANIFEST_IMPORTED", "application:" + app, null, app,
                "新建权限 " + permissionsCreated + "、更新权限 " + permissionsUpdated
                        + "、新建角色 " + rolesCreated + "、更新角色 " + rolesUpdated);
        return new ManifestImportResult(permissionsCreated, permissionsUpdated, rolesCreated, rolesUpdated);
    }

    @Override
    public PermissionHoldersResult permissionHolders(long app, String code, Long department, Collection<Long> visibleDepartments) {
        if (!applications.existsById(app)) {
            throw ApiException.missing("软件不存在");
        }
        Map<HolderKey, UserRoleRepository.PermissionHolderRow> firstByHolder = new LinkedHashMap<>();
        Map<HolderKey, List<PermissionHolderRoleResult>> rolesByHolder = new LinkedHashMap<>();
        for (UserRoleRepository.PermissionHolderRow row : userRoles.findPermissionHolders(app, code, department, visibleDepartments, HOLDER_LIMIT)) {
            HolderKey key = new HolderKey(row.userId(), row.departmentId());
            firstByHolder.putIfAbsent(key, row);
            rolesByHolder.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new PermissionHolderRoleResult(row.roleId(), row.roleName(), row.roleCode(), row.expiresAt()));
        }
        List<PermissionHolderResult> holders = firstByHolder.entrySet().stream().map(e -> {
            UserRoleRepository.PermissionHolderRow h = e.getValue();
            return new PermissionHolderResult(h.userId(), h.userName(), h.externalId(), h.departmentId(), h.departmentName(),
                    rolesByHolder.get(e.getKey()));
        }).toList();
        long total = userRoles.countPermissionHolders(app, code, department, visibleDepartments);
        return new PermissionHoldersResult(code, app, holders, total, total > holders.size());
    }

    @Override
    public List<ExpiringAssignmentResult> expiringAssignments(int days, Visibility visible, Long app) {
        return userRoles.findExpiringAssignments(Instant.now().plus(days, ChronoUnit.DAYS), visible, app).stream()
                .map(e -> new ExpiringAssignmentResult(e.userId(), e.userName(), e.externalId(), e.roleId(), e.roleName(),
                        e.roleCode(), e.departmentId(), e.departmentName(), e.applicationId(), e.applicationName(),
                        e.expiresAt(), e.grantedBy(), e.grantedAt()))
                .toList();
    }

    private static AccessRequestResult requestResult(AccessRequestRepository.AccessRequestRow r) {
        return new AccessRequestResult(
                r.id(), r.userId(), r.userName(), r.externalId(), r.departmentId(), r.departmentName(),
                r.applicationId(), r.applicationName(), r.roleId(), r.roleName(), r.roleCode(), r.roleEnabled(),
                r.reason(), r.durationDays(), r.status(), r.requestedBy(), r.decidedBy(), r.decidedAt(),
                r.decisionComment(), r.grantExpiresAt(), r.createdAt());
    }

    private static String text(String value) {
        return Objects.requireNonNullElse(value, "").strip();
    }
}
