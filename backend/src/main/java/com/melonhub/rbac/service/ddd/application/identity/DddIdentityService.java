package com.melonhub.rbac.service.ddd.application.identity;

import com.melonhub.rbac.service.ddd.application.shared.DddBoundary;
import com.melonhub.rbac.service.ddd.domain.model.IdentityAccountAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.IdentityRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PageSlice;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CryptoGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.IdentityTokenGateway;
import com.melonhub.rbac.service.ddd.domain.value.CoursePermissionCatalog;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.PasswordPolicy;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.identity.IdentityModels.CourseAuditActorResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.CourseAuditEventResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.CreateUserWithPasswordCommand;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityApiPermissionCheckResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityCurrentUserResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityDepartmentResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityDepartmentUserResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityLoginResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityPageResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityPermissionCheckResult;
import com.melonhub.rbac.service.port.identity.IdentityModels.IdentityUserLookupResult;
import com.melonhub.rbac.service.port.identity.IdentityUseCase;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveUserCommand;
import com.melonhub.rbac.service.port.organization.OrganizationUseCase;
import com.melonhub.rbac.service.port.security.AdminFunction;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DDD 统一身份应用服务：委托 {@link IdentityAccountAggregate} 与 {@link CoursePermissionCatalog} 判定课程权限与日志投影。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class DddIdentityService implements IdentityUseCase {
    private final IdentityRepositoryPort repository;
    private final OrganizationUseCase organization;
    private final AuditUseCase audit;
    private final CryptoGateway crypto;
    private final IdentityTokenGateway tokens;

    public DddIdentityService(IdentityRepositoryPort repository, OrganizationUseCase organization,
                              AuditUseCase audit, CryptoGateway crypto, IdentityTokenGateway tokens) {
        this.repository = repository;
        this.organization = organization;
        this.audit = audit;
        this.crypto = crypto;
        this.tokens = tokens;
    }

    @Override
    @Transactional
    public IdentityLoginResult login(String username, String password, List<String> systems) {
        return DddBoundary.execute(() -> {
            IdentityAccountAggregate.validateLoginSystems(systems);
            IdentityAccountAggregate.IdentityCredentialSnapshot credential = repository.findCredential(username)
                    .orElseThrow(() -> DomainException.unauthorized("UNAUTHORIZED", "账号或密码错误。"));
            if (!crypto.matchesIdentityPassword(password, credential.passwordHash())) {
                throw DomainException.unauthorized("UNAUTHORIZED", "账号或密码错误。");
            }
            Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            String token = tokens.issue(credential.username(), systems, issuedAt);
            IdentityTokenGateway.VerifiedToken claims = tokens.verify(token, CoursePermissionCatalog.COURSE_SYSTEM);
            if (!repository.createSession(crypto.hashToken(token), credential, CoursePermissionCatalog.COURSE_SYSTEM, claims.expiresAt())) {
                throw DomainException.unauthorized("UNAUTHORIZED", "账号或密码错误。");
            }
            return new IdentityLoginResult(token, "Bearer", claims.expiresAt().getEpochSecond() - issuedAt.getEpochSecond());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityPageResult<CourseAuditEventResult> auditLogs(String authorization, String actor, Long actorId, String target,
                                                                OffsetDateTime from, OffsetDateTime to, int page, int pageSize) {
        return DddBoundary.execute(() -> {
            IdentityAccountAggregate.IdentityUser user = authenticatedUser(authorization);
            List<Long> departments = repository.permissionDepartments(user.id(), CoursePermissionCatalog.COURSE_SYSTEM,
                    CoursePermissionCatalog.PERMISSION_AUDIT_VIEW);
            IdentityAccountAggregate.requireCourseAuditDepartments(departments);
            PageSlice<IdentityAccountAggregate.CourseAuditRecord> events = repository.courseAuditEvents(
                    CoursePermissionCatalog.COURSE_SYSTEM, departments, actor, actorId, target,
                    from == null ? null : from.toInstant(), to == null ? null : to.toInstant(), page, pageSize);
            List<Long> actorIds = events.items().stream().map(IdentityAccountAggregate.CourseAuditRecord::actorId)
                    .filter(Objects::nonNull).distinct().toList();
            Map<Long, List<String>> roles = repository.auditActorRoles(actorIds, CoursePermissionCatalog.COURSE_SYSTEM, departments);
            List<CourseAuditEventResult> items = events.items().stream().map(row -> {
                IdentityAccountAggregate.CourseAuditProjection p = IdentityAccountAggregate.projectCourseAuditEvent(row, roles);
                return new CourseAuditEventResult(p.id(), p.at(),
                        new CourseAuditActorResult(p.actor().id(), p.actor().name(), p.actor().roles(), p.actor().deleted()),
                        p.type(), p.target());
            }).toList();
            return new IdentityPageResult<>(page, pageSize, events.total(), items);
        });
    }

    @Override
    @Transactional
    public void logout(String authorization) {
        DddBoundary.run(() -> {
            String token = IdentityAccountAggregate.extractBearerToken(authorization);
            tokens.verify(token, CoursePermissionCatalog.COURSE_SYSTEM);
            repository.revokeSession(crypto.hashToken(token), Instant.now());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityCurrentUserResult currentUser(String authorization) {
        return DddBoundary.execute(() -> {
            IdentityAccountAggregate.Profile p = loadAggregate(authenticatedUser(authorization)).profile();
            return new IdentityCurrentUserResult(p.subject(), p.roles(), p.permissions(), p.name(), p.no());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityPageResult<IdentityDepartmentResult> departments(String authorization, String keyword, Long parentId,
                                                                    int page, int pageSize) {
        return DddBoundary.execute(() -> {
            loadAggregate(authenticatedUser(authorization)).requireOrganizationReadAccess();
            List<IdentityDepartmentResult> items = repository.departments(keyword, parentId, page, pageSize).stream()
                    .map(row -> new IdentityDepartmentResult(row.id(), row.code(), row.name(), row.parentId(), row.hasChildren()))
                    .toList();
            return new IdentityPageResult<>(page, pageSize, repository.departmentCount(keyword, parentId), items);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityPageResult<IdentityDepartmentUserResult> departmentUsers(String authorization, long departmentId,
                                                                            String keyword, int page, int pageSize) {
        return DddBoundary.execute(() -> {
            loadAggregate(authenticatedUser(authorization)).requireOrganizationReadAccess();
            if (!repository.departmentExists(departmentId)) {
                throw new DomainException(DomainException.Kind.NOT_FOUND, "IDENTITY_DEPARTMENT_NOT_FOUND",
                        "统一身份认证系统中没有找到该部门。");
            }
            List<IdentityDepartmentUserResult> items = repository.departmentUsers(departmentId, keyword, page, pageSize).stream()
                    .map(row -> new IdentityDepartmentUserResult(row.userId(), row.no(), row.name(), row.departmentId(), row.departmentName()))
                    .toList();
            return new IdentityPageResult<>(page, pageSize, repository.departmentUserCount(departmentId, keyword), items);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityPermissionCheckResult checkPermission(String authorization, String permission) {
        return DddBoundary.execute(() -> {
            IdentityAccountAggregate.IdentityUser user = authenticatedUser(authorization);
            IdentityAccountAggregate aggregate = new IdentityAccountAggregate(user, List.of(),
                    repository.permissions(user.id(), CoursePermissionCatalog.COURSE_SYSTEM));
            return new IdentityPermissionCheckResult(permission, aggregate.hasUiPermission(permission));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityApiPermissionCheckResult checkApiPermission(String authorization, String operationId) {
        return DddBoundary.execute(() -> {
            IdentityAccountAggregate.IdentityUser user = authenticatedUser(authorization);
            String required = CoursePermissionCatalog.requirePermissionForOperation(operationId);
            IdentityAccountAggregate aggregate = new IdentityAccountAggregate(user, List.of(),
                    repository.permissions(user.id(), CoursePermissionCatalog.COURSE_SYSTEM));
            return new IdentityApiPermissionCheckResult(operationId, aggregate.hasUiPermission(required));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityUserLookupResult lookupUser(String authorization, String userId) {
        return DddBoundary.execute(() -> {
            loadAggregate(authenticatedUser(authorization)).requireUserLookupAccess();
            IdentityAccountAggregate.IdentityUser user = repository.findUserByExternalId(userId)
                    .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "IDENTITY_USER_NOT_FOUND",
                            "统一身份认证系统中没有找到该用户。"));
            return new IdentityUserLookupResult(user.externalId(), user.externalId(), user.name());
        });
    }

    @Override
    @Transactional
    public long provisionUser(SaveUserCommand body, String password, String actor) {
        return DddBoundary.execute(() -> {
            Long existingId = repository.findAnyUserIdByExternalId(body.externalId()).orElse(null);
            long userId = organization.user(body, existingId, actor);
            savePassword(userId, body.externalId(), password, actor);
            return userId;
        });
    }

    @Override
    @Transactional
    public long createUser(CreateUserWithPasswordCommand body, AdminPrincipal principal) {
        return DddBoundary.execute(() -> {
            if (body.identityPassword() != null && !principal.canUse(AdminFunction.USER_CREDENTIALS)) {
                throw DomainException.forbidden("当前账号没有管理用户登录凭据功能");
            }
            long userId = organization.user(body.profile(), null, principal.actor(), principal.visibleDepartments());
            if (body.identityPassword() != null) {
                savePassword(userId, body.externalId(), body.identityPassword(), principal.actor());
            }
            return userId;
        });
    }

    @Override
    @Transactional
    public void setUserPassword(long userId, String password, AdminPrincipal principal) {
        DddBoundary.run(() -> {
            String username = organization.lockOwnedUser(userId, principal.visibleDepartments());
            savePassword(userId, username, password, principal.actor());
        });
    }

    private void savePassword(long userId, String username, String password, String actor) {
        PasswordPolicy.validateIdentityPassword(password);
        repository.upsertCredential(userId, username, crypto.encodeIdentityPassword(password));
        repository.revokeUserSessions(userId);
        audit.audit(actor, "USER_IDENTITY_PASSWORD_SET", username, null, null, "统一身份会话已结束");
    }

    private IdentityAccountAggregate.IdentityUser authenticatedUser(String authorization) {
        String token = IdentityAccountAggregate.extractBearerToken(authorization);
        IdentityTokenGateway.VerifiedToken claims = tokens.verify(token, CoursePermissionCatalog.COURSE_SYSTEM);
        if (!repository.hasActiveSession(crypto.hashToken(token), Instant.now())) {
            throw DomainException.unauthorized("UNAUTHORIZED", "请先登录或令牌已失效。");
        }
        return repository.findUserByExternalId(claims.subject())
                .orElseThrow(() -> DomainException.unauthorized("UNAUTHORIZED", "请先登录。"));
    }

    private IdentityAccountAggregate loadAggregate(IdentityAccountAggregate.IdentityUser user) {
        return new IdentityAccountAggregate(
                user,
                repository.roles(user.id(), CoursePermissionCatalog.COURSE_SYSTEM),
                repository.permissions(user.id(), CoursePermissionCatalog.COURSE_SYSTEM));
    }
}
