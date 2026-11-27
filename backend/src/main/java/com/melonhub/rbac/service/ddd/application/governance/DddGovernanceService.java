package com.melonhub.rbac.service.ddd.application.governance;

import com.melonhub.rbac.service.ddd.application.shared.DddBoundary;
import com.melonhub.rbac.service.ddd.domain.model.AccessRequestAggregate;
import com.melonhub.rbac.service.ddd.domain.model.AccessReviewAggregate;
import com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.GovernanceRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PageSlice;
import com.melonhub.rbac.service.ddd.domain.repository.PermissionContextRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CacheGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.DomainEventPublisher;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.TransactionRunner;
import com.melonhub.rbac.service.ddd.domain.repository.RbacRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.IsolationDomain;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacUseCase;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DDD 授权治理应用服务：委托 {@link AccessRequestAggregate} 与 {@link AccessReviewAggregate} 守护状态机与业务规则。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class DddGovernanceService implements GovernanceUseCase {
    private record UserDomainKey(long userId, long departmentId, long applicationId) {}
    private record HolderKey(long userId, long departmentId) {}

    private final GovernanceRepositoryPort governance;
    private final RbacRepositoryPort rbacRepo;
    private final PermissionContextRepositoryPort contexts;
    private final CacheGateway cache;
    private final OrganizationUseCase organization;
    private final DomainRbacUseCase domainRbac;
    private final AuditUseCase audit;
    private final DomainEventPublisher events;
    private final TransactionRunner transactions;

    public DddGovernanceService(GovernanceRepositoryPort governance, RbacRepositoryPort rbacRepo,
                                PermissionContextRepositoryPort contexts, CacheGateway cache,
                                OrganizationUseCase organization, DomainRbacUseCase domainRbac,
                                AuditUseCase audit, DomainEventPublisher events,
                                TransactionRunner transactions) {
        this.governance = governance;
        this.rbacRepo = rbacRepo;
        this.contexts = contexts;
        this.cache = cache;
        this.organization = organization;
        this.domainRbac = domainRbac;
        this.audit = audit;
        this.events = events;
        this.transactions = transactions;
    }

    @Override
    public PageResult<AccessRequestResult> requests(Long app, Visibility visible, String status, String keyword, int page, int size) {
        return DddBoundary.execute(() -> {
            AccessRequestAggregate.validateStatusFilter(status);
            PageSlice<GovernanceRepositoryPort.AccessRequestRecord> slice =
                    governance.requests(app, DddBoundary.toDomainVisibility(visible), status, keyword, page, size);
            return PageResult.of(slice.items().stream().map(DddGovernanceService::toRequestResult).toList(),
                    slice.total(), page, size);
        });
    }

    @Override
    public AccessRequestResult request(long id) {
        return DddBoundary.execute(() -> governance.requestView(id)
                .map(DddGovernanceService::toRequestResult)
                .orElseThrow(() -> DomainException.missing("申请不存在")));
    }

    @Override
    @Transactional
    public AccessRequestResult submit(long app, SubmitAccessRequestCommand body, String actor) {
        return DddBoundary.execute(() -> {
            long user = rbacRepo.lockUserByExternalId(body.externalId())
                    .orElseThrow(() -> DomainException.missing("用户不存在"));
            AccessRequestAggregate.validateSubmission(
                    rbacRepo.isLockedMember(user, body.departmentId()),
                    rbacRepo.roleExistsInDomain(body.roleId(), body.departmentId(), app),
                    governance.pendingRequestExists(user, body.roleId()));
            long id = governance.createRequest(user, body.departmentId(), app, body.roleId(),
                    body.reason().strip(), body.durationDays(), actor);
            audit.audit(actor, "ACCESS_REQUESTED", body.externalId(), body.departmentId(), app, "角色 #" + body.roleId());
            return request(id);
        });
    }

    @Override
    @Transactional
    public AccessRequestResult approve(long id, ApproveRequestCommand body, String actor) {
        return DddBoundary.execute(() -> {
            AccessRequestAggregate aggregate = governance.findRequest(id)
                    .orElseThrow(() -> DomainException.missing("申请不存在"));
            AccessRequestAggregate.ApprovalPlan plan = aggregate.planApproval(
                    body.permanent(), body.durationDays(), body.comment(), Instant.now());
            rbacRepo.lockUser(plan.userId()).orElseThrow(() -> DomainException.missing("用户不存在"));
            if (!rbacRepo.isLockedMember(plan.userId(), plan.departmentId())) {
                throw DomainException.conflict("用户已不属于该部门，无法授权");
            }
            rbacRepo.grantRole(plan.userId(), plan.departmentId(), plan.applicationId(), plan.roleId(), plan.expiresAt(), actor);
            if (governance.decideRequest(id, "APPROVED", actor, plan.comment(), plan.expiresAt()) == 0) {
                throw DomainException.conflict("申请已被并发处理");
            }
            contexts.revokeDomain(plan.userId(), plan.departmentId(), plan.applicationId());
            cache.touchUser(plan.userId());
            events.emit(plan.applicationId(), "access.approved", Map.of(
                    "userId", plan.userId(), "externalId", plan.externalId(),
                    "departmentId", plan.departmentId(), "roleId", plan.roleId()));
            audit.audit(actor, "ACCESS_REQUEST_APPROVED", plan.externalId(), plan.departmentId(), plan.applicationId(),
                    plan.auditDetail());
            return request(id);
        });
    }

    @Override
    @Transactional
    public AccessRequestResult reject(long id, RejectRequestCommand body, String actor) {
        return DddBoundary.execute(() -> {
            AccessRequestAggregate aggregate = governance.findRequest(id)
                    .orElseThrow(() -> DomainException.missing("申请不存在"));
            aggregate.assertRejectable();
            String comment = text(body.comment());
            if (governance.decideRequest(id, "REJECTED", actor, comment, null) == 0) {
                throw DomainException.conflict("申请已被并发处理");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("userId", aggregate.userId());
            data.put("externalId", aggregate.externalId());
            data.put("departmentId", aggregate.departmentId());
            data.put("roleId", aggregate.roleId());
            data.put("comment", comment);
            events.emit(aggregate.applicationId(), "access.rejected", data);
            audit.audit(actor, "ACCESS_REQUEST_REJECTED", aggregate.externalId(), aggregate.departmentId(),
                    aggregate.applicationId(), "角色 " + aggregate.roleCode()
                            + (body.comment() == null || body.comment().isBlank() ? "" : "，理由：" + body.comment()));
            return request(id);
        });
    }

    @Override
    @Transactional
    public void cancelOwn(long app, long id, String externalId, String actor) {
        DddBoundary.run(() -> {
            long user = rbacRepo.userIdByExternalId(externalId).orElseThrow(() -> DomainException.missing("用户不存在"));
            AccessRequestAggregate aggregate = governance.findRequest(id)
                    .orElseThrow(() -> DomainException.missing("申请不存在"));
            aggregate.assertOwnedByApplicant(app, user);
            if (governance.cancelOwnRequest(id, user) == 0) {
                throw DomainException.conflict("申请已被处理，无法撤回");
            }
            audit.audit(actor, "ACCESS_REQUEST_CANCELLED", externalId, aggregate.departmentId(), app,
                    "角色 " + aggregate.roleCode());
        });
    }

    @Override
    public List<AccessRequestResult> myRequests(long app, String externalId) {
        return DddBoundary.execute(() -> {
            long user = rbacRepo.userIdByExternalId(externalId).orElseThrow(() -> DomainException.missing("用户不存在"));
            return governance.requestsOfUser(user, app).stream().map(DddGovernanceService::toRequestResult).toList();
        });
    }

    @Override
    public SelfAccessResult myAccess(long app, String externalId, long dept) {
        return DddBoundary.execute(() -> {
            long user = rbacRepo.userIdByExternalId(externalId).orElseThrow(() -> DomainException.missing("用户不存在"));
            domainRbac.domain(dept, app);
            if (!rbacRepo.isMember(user, dept)) {
                throw DomainException.forbidden("用户不属于目标部门");
            }
            DomainAuthorizationAggregate aggregate = new DomainAuthorizationAggregate(new IsolationDomain(dept, app));
            DomainAuthorizationAggregate.SelfAccessPreparation prep =
                    aggregate.prepareSelfAccess(rbacRepo.assignedRolesInDomain(user, dept, app), Instant.now());
            List<DomainAuthorizationAggregate.PermissionGrant> grants = rbacRepo.permissionGrants(dept, app, prep.usableRoleIds());
            DomainAuthorizationAggregate.SelfAccessReport report = aggregate.buildSelfAccessReport(user, prep, grants);
            List<SelfAccessRoleResult> roles = report.roles().stream()
                    .map(r -> new SelfAccessRoleResult(r.id(), r.name(), r.code(), r.enabled(), r.expiresAt())).toList();
            return new SelfAccessResult(report.userId(), report.departmentId(), report.applicationId(), roles, report.permissions());
        });
    }

    @Override
    @Transactional
    public ReviewDetailResult createReview(CreateReviewCommand body, String actor) {
        return DddBoundary.execute(() -> {
            if (!rbacRepo.applicationExists(body.applicationId())) {
                throw DomainException.missing("软件不存在");
            }
            if (body.departmentId() != null && !rbacRepo.departmentExists(body.departmentId())) {
                throw DomainException.missing("部门不存在");
            }
            Instant due = body.dueDays() == null ? null
                    : Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(body.dueDays(), ChronoUnit.DAYS);
            long id = governance.createReview(body.name().strip(), body.applicationId(), body.departmentId(), due, actor);
            int items = governance.insertReviewSnapshot(id, body.applicationId(), body.departmentId());
            audit.audit(actor, "REVIEW_CREATED", body.name(), body.departmentId(), body.applicationId(),
                    "快照包含 " + items + " 条授权");
            return review(id);
        });
    }

    @Override
    public ReviewDetailResult review(long id) {
        return DddBoundary.execute(() -> governance.reviewView(id)
                .map(r -> new ReviewDetailResult(
                        r.id(), r.name(), r.applicationId(), r.applicationName(), r.departmentId(), r.departmentName(),
                        r.status(), r.dueAt(), r.createdBy(), r.createdAt(), r.closedBy(), r.closedAt(), r.summary(),
                        r.items(), r.pending(), r.kept(), r.revoked(), r.skipped()))
                .orElseThrow(() -> DomainException.missing("复核不存在")));
    }

    @Override
    public List<ReviewSummaryResult> reviews(Long app, Visibility visible, String status) {
        return DddBoundary.execute(() -> {
            AccessReviewAggregate.validateStatusFilter(status);
            return governance.reviews(app, DddBoundary.toDomainVisibility(visible), status).stream()
                    .map(r -> new ReviewSummaryResult(
                            r.id(), r.name(), r.applicationId(), r.applicationName(), r.departmentId(), r.departmentName(),
                            r.status(), r.dueAt(), r.createdBy(), r.createdAt(), r.closedBy(), r.closedAt(),
                            r.items(), r.pending(), r.kept(), r.revoked(), r.skipped()))
                    .toList();
        });
    }

    @Override
    public PageResult<ReviewItemResult> reviewItems(long reviewId, String decision, int page, int size) {
        return DddBoundary.execute(() -> {
            review(reviewId);
            AccessReviewAggregate.validateDecisionFilter(decision);
            PageSlice<GovernanceRepositoryPort.ReviewItemRecord> slice = governance.reviewItems(reviewId, decision, page, size);
            return PageResult.of(slice.items().stream().map(i -> new ReviewItemResult(
                            i.id(), i.userId(), i.userName(), i.externalId(), i.roleId(), i.roleName(), i.roleCode(),
                            i.departmentId(), i.departmentName(), i.decision(), i.comment(), i.decidedBy(), i.decidedAt(),
                            i.applied(), i.skipped())).toList(),
                    slice.total(), page, size);
        });
    }

    @Override
    @Transactional
    public void decideReviewItem(long reviewId, long itemId, DecideReviewItemCommand body, String actor) {
        DddBoundary.run(() -> {
            lockOpenReview(reviewId);
            if (governance.decideReviewItem(itemId, reviewId, body.decision(), actor, text(body.comment())) == 0) {
                throw DomainException.missing("复核条目不存在");
            }
        });
    }

    @Override
    @Transactional
    public ReviewDetailResult closeReview(long reviewId, String actor) {
        return DddBoundary.execute(() -> {
            lockOpenReview(reviewId);
            AccessReviewAggregate review = governance.findReview(reviewId)
                    .orElseThrow(() -> DomainException.missing("复核不存在"));
            List<AccessReviewAggregate.RevocationCandidate> revocations = governance.pendingRevocations(reviewId);
            rbacRepo.lockUsers(AccessReviewAggregate.orderedUsersToLock(revocations));
            Map<Long, List<Long>> domains = new LinkedHashMap<>();
            Set<Long> touched = new LinkedHashSet<>();
            int revoked = 0;
            int skipped = 0;
            for (AccessReviewAggregate.RevocationCandidate item : revocations) {
                boolean applied = item.canAttemptRevoke()
                        && governance.revokeMatchingGrant(item.userId(), item.roleId(), item.grantId()) > 0;
                governance.markReviewItemApplied(item.itemId(), !applied);
                if (!applied) {
                    skipped++;
                    continue;
                }
                revoked++;
                touched.add(item.userId());
                domains.computeIfAbsent(item.departmentId(), k -> new ArrayList<>()).add(item.userId());
            }
            for (Map.Entry<Long, List<Long>> entry : domains.entrySet()) {
                contexts.revokeDomainForUsers(entry.getValue(), entry.getKey(), review.applicationId());
            }
            for (long user : touched) {
                cache.touchUser(user);
            }
            String summary = AccessReviewAggregate.closeSummary(revoked, skipped);
            if (governance.closeReview(reviewId, actor, summary) == 0) {
                throw DomainException.conflict("复核已被并发关闭");
            }
            if (revoked > 0) {
                events.emit(review.applicationId(), "review.closed", Map.of("reviewId", reviewId, "revoked", revoked));
            }
            audit.audit(actor, "REVIEW_CLOSED", review.name(), review.departmentId(), review.applicationId(), summary);
            return review(reviewId);
        });
    }

    @Override
    @Transactional
    public int expireAssignments(int limit) {
        Map<UserDomainKey, List<RbacRepositoryPort.LapsedAssignmentRecord>> lapsed = new LinkedHashMap<>();
        for (RbacRepositoryPort.LapsedAssignmentRecord row : rbacRepo.lapsedAssignments(limit)) {
            if (rbacRepo.deleteLapsedAssignment(row.userId(), row.roleId()) == 0) {
                continue;
            }
            UserDomainKey key = new UserDomainKey(row.userId(), row.departmentId(), row.applicationId());
            lapsed.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        int expired = 0;
        for (List<RbacRepositoryPort.LapsedAssignmentRecord> rows : lapsed.values()) {
            RbacRepositoryPort.LapsedAssignmentRecord first = rows.get(0);
            long user = first.userId();
            long dept = first.departmentId();
            long app = first.applicationId();
            String external = first.externalId();
            cache.touchUser(user);
            events.emit(app, "user.roles.expired", Map.of("userId", user, "externalId", external, "departmentId", dept,
                    "roleIds", rows.stream().map(RbacRepositoryPort.LapsedAssignmentRecord::roleId).toList()));
            audit.audit("system", "USER_ROLES_EXPIRED", external, dept, app,
                    "到期角色 " + rows.stream().map(RbacRepositoryPort.LapsedAssignmentRecord::roleCode).collect(Collectors.joining("、")));
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
        for (RbacRepositoryPort.DepartmentCodeId row : rbacRepo.departmentIdsByCodes(codes)) {
            departmentByCode.put(row.code(), row.id());
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
                        throw DomainException.missing("部门编码不存在: " + code);
                    }
                    wanted.add(dept);
                }
                String email = row.email() == null ? "" : row.email().strip();
                boolean createdRow = Boolean.TRUE.equals(transactions.inTransaction(() -> {
                    Optional<Long> existing = rbacRepo.lockUserByExternalId(row.externalId());
                    long userId;
                    boolean isNew;
                    if (existing.isPresent()) {
                        userId = existing.get();
                        rbacRepo.updateUserProfile(userId, row.name().strip(), email);
                        isNew = false;
                    } else {
                        userId = rbacRepo.createUser(row.name().strip(), row.externalId(), email);
                        isNew = true;
                    }
                    Set<Long> current = new HashSet<>(rbacRepo.lockedUserDepartments(userId));
                    for (long dept : wanted) {
                        if (!current.contains(dept)) {
                            rbacRepo.addMembership(userId, dept);
                        }
                    }
                    return isNew;
                }));
                if (createdRow) {
                    created++;
                } else {
                    updated++;
                }
            } catch (DomainException | DataIntegrityViolationException e) {
                failures.add(new UserImportFailureResult(
                        line,
                        row == null ? null : row.externalId(),
                        e instanceof DomainException ? e.getMessage() : "外部标识已存在或关联数据冲突"));
            }
        }
        audit.audit(actor, "USER_IMPORTED", "users", null, null,
                "新建 " + created + "，更新 " + updated + "，失败 " + failures.size());
        return new UserImportResult(created, updated, failures);
    }

    @Override
    @Transactional(readOnly = true)
    public PermissionManifestResult exportManifest(long app, Long departmentFilter, Collection<Long> visibleDepartments) {
        return DddBoundary.execute(() -> {
            RbacRepositoryPort.ApplicationRefRecord application = rbacRepo.applicationRow(app)
                    .orElseThrow(() -> DomainException.missing("软件不存在"));
            Map<Long, DepartmentResult> departmentsById = new HashMap<>();
            for (DepartmentResult department : organization.departments(null)) {
                departmentsById.put(department.id(), department);
            }
            List<Long> departmentIds = departmentFilter != null ? List.of(departmentFilter)
                    : rbacRepo.manifestDepartmentIds(app).stream()
                            .filter(id -> visibleDepartments == null || visibleDepartments.contains(id)).toList();
            List<ManifestDepartmentResult> sections = new ArrayList<>();
            for (long dept : departmentIds) {
                DepartmentResult department = departmentsById.get(dept);
                if (department == null) {
                    continue;
                }
                List<ManifestPermissionResult> permissions = new ArrayList<>();
                Map<Long, String> codesById = new HashMap<>();
                for (RbacRepositoryPort.PermissionRecord p : rbacRepo.permissions(dept, app)) {
                    codesById.put(p.id(), p.code());
                    permissions.add(new ManifestPermissionResult(p.code(), p.name(), p.resource(), p.action()));
                }
                Map<Long, List<String>> grantsByRole = new HashMap<>();
                for (RbacRepositoryPort.RolePermissionPair pair : rbacRepo.domainRolePermissionPairs(dept, app)) {
                    String code = codesById.get(pair.permissionId());
                    if (code != null) {
                        grantsByRole.computeIfAbsent(pair.roleId(), k -> new ArrayList<>()).add(code);
                    }
                }
                List<ManifestRoleResult> roles = new ArrayList<>();
                for (RbacRepositoryPort.RoleRecord r : rbacRepo.roles(dept, app)) {
                    roles.add(new ManifestRoleResult(r.code(), r.name(), r.description(), r.enabled(),
                            grantsByRole.getOrDefault(r.id(), List.of())));
                }
                sections.add(new ManifestDepartmentResult(department.code(), department.name(), permissions, roles));
            }
            return new PermissionManifestResult(1, new ManifestApplicationResult(application.code(), application.name()),
                    Instant.now().toString(), sections);
        });
    }

    @Override
    @Transactional
    public ManifestImportResult importManifest(long app, ManifestImportCommand manifest, String actor, Collection<Long> scope) {
        return DddBoundary.execute(() -> {
            if (!rbacRepo.applicationExists(app)) {
                throw DomainException.missing("软件不存在");
            }
            List<String> codes = manifest.departments().stream().map(ManifestDepartmentCommand::code).toList();
            Map<String, Long> departmentByCode = new HashMap<>();
            for (RbacRepositoryPort.DepartmentCodeId row : rbacRepo.departmentIdsByCodes(codes)) {
                departmentByCode.put(row.code(), row.id());
            }
            for (String code : codes) {
                if (!departmentByCode.containsKey(code)) {
                    throw DomainException.missing("部门编码不存在: " + code);
                }
            }
            if (scope != null && !scope.containsAll(departmentByCode.values())) {
                throw DomainException.forbidden("清单中包含你管理范围以外的部门");
            }
            int permissionsCreated = 0;
            int permissionsUpdated = 0;
            int rolesCreated = 0;
            int rolesUpdated = 0;
            for (ManifestDepartmentCommand section : manifest.departments()) {
                long dept = departmentByCode.get(section.code());
                Map<Long, Boolean> wasEnabled = new HashMap<>();
                for (RbacRepositoryPort.RoleRecord role : rbacRepo.roles(dept, app)) {
                    wasEnabled.put(role.id(), role.enabled());
                }
                Map<String, Long> permissionIds = new HashMap<>();
                for (ManifestPermissionCommand p : section.permissions()) {
                    Optional<Long> existing = rbacRepo.permissionIdByCode(dept, app, p.code());
                    if (existing.isPresent()) {
                        rbacRepo.updatePermission(existing.get(), p.name(), p.code(), p.resource(), p.action());
                        permissionIds.put(p.code(), existing.get());
                        permissionsUpdated++;
                    } else {
                        permissionIds.put(p.code(), rbacRepo.createPermission(dept, app, p.name(), p.code(), p.resource(), p.action()));
                        permissionsCreated++;
                    }
                }
                for (ManifestRoleCommand r : section.roles()) {
                    Set<Long> grants = new LinkedHashSet<>();
                    for (String code : r.permissions()) {
                        Long permissionId = permissionIds.get(code);
                        if (permissionId == null) {
                            permissionId = rbacRepo.permissionIdByCode(dept, app, code)
                                    .orElseThrow(() -> DomainException.bad("角色 " + r.code() + " 引用了不存在的权限编码 " + code));
                        }
                        grants.add(permissionId);
                    }
                    boolean enabled = r.enabled() == null || r.enabled();
                    Long roleId = rbacRepo.roleIdByCode(dept, app, r.code()).orElse(null);
                    if (roleId == null) {
                        rbacRepo.replaceRolePermissions(
                                rbacRepo.createRole(dept, app, r.name(), r.code(), text(r.description()), enabled),
                                dept, app, grants);
                        rolesCreated++;
                    } else {
                        Set<Long> before = new HashSet<>(rbacRepo.rolePermissionIds(roleId));
                        rbacRepo.updateRole(roleId, r.name(), r.code(), text(r.description()));
                        rbacRepo.updateRoleStatus(roleId, enabled);
                        rbacRepo.replaceRolePermissions(roleId, dept, app, grants);
                        if (enabled != Boolean.TRUE.equals(wasEnabled.get(roleId))) {
                            events.emit(app, enabled ? "role.enabled" : "role.disabled", Map.of("roleId", roleId, "departmentId", dept));
                        }
                        if (!before.equals(grants)) {
                            events.emit(app, "role.permissions.changed", Map.of("roleId", roleId, "departmentId", dept));
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
        });
    }

    @Override
    public PermissionHoldersResult permissionHolders(long app, String code, Long department, Collection<Long> visibleDepartments) {
        return DddBoundary.execute(() -> {
            if (!rbacRepo.applicationExists(app)) {
                throw DomainException.missing("软件不存在");
            }
            Map<HolderKey, RbacRepositoryPort.PermissionHolderRecord> firstByHolder = new LinkedHashMap<>();
            Map<HolderKey, List<PermissionHolderRoleResult>> rolesByHolder = new LinkedHashMap<>();
            for (RbacRepositoryPort.PermissionHolderRecord row : rbacRepo.permissionHolders(app, code, department, visibleDepartments, HOLDER_LIMIT)) {
                HolderKey key = new HolderKey(row.userId(), row.departmentId());
                firstByHolder.putIfAbsent(key, row);
                rolesByHolder.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new PermissionHolderRoleResult(row.roleId(), row.roleName(), row.roleCode(), row.expiresAt()));
            }
            List<PermissionHolderResult> holders = firstByHolder.entrySet().stream().map(e -> {
                RbacRepositoryPort.PermissionHolderRecord h = e.getValue();
                return new PermissionHolderResult(h.userId(), h.userName(), h.externalId(), h.departmentId(), h.departmentName(),
                        rolesByHolder.get(e.getKey()));
            }).toList();
            long total = rbacRepo.permissionHolderCount(app, code, department, visibleDepartments);
            return new PermissionHoldersResult(code, app, holders, total, total > holders.size());
        });
    }

    @Override
    public List<ExpiringAssignmentResult> expiringAssignments(int days, Visibility visible, Long app) {
        return rbacRepo.expiringAssignments(Instant.now().plus(days, ChronoUnit.DAYS),
                        DddBoundary.toDomainVisibility(visible), app).stream()
                .map(e -> new ExpiringAssignmentResult(e.userId(), e.userName(), e.externalId(), e.roleId(), e.roleName(),
                        e.roleCode(), e.departmentId(), e.departmentName(), e.applicationId(), e.applicationName(),
                        e.expiresAt(), e.grantedBy(), e.grantedAt()))
                .toList();
    }

    private void lockOpenReview(long reviewId) {
        String status = governance.lockReviewStatus(reviewId).orElseThrow(() -> DomainException.missing("复核不存在"));
        AccessReviewAggregate.assertOpenStatus(status);
    }

    private static void validateImportRow(UserImportRowCommand row) {
        if (row == null) {
            throw DomainException.bad("该行为空");
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
            throw DomainException.bad("字段 " + String.join("、", fields) + " 缺失或格式不正确");
        }
    }

    private static AccessRequestResult toRequestResult(GovernanceRepositoryPort.AccessRequestRecord r) {
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
