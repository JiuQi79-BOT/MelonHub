package com.melonhub.rbac.service.structured.identity;

import com.melonhub.rbac.infrastructure.identity.IdentityTokenService;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepositoryCustom;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.IdentityCredentialRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.IdentitySessionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.MembershipRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleInheritanceRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RolePermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRoleRepository;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.error.ApiException;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structured 统一身份认证与课程系统权限查询服务。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "structured", matchIfMissing = true)
public class StructuredIdentityService implements IdentityUseCase {
    private static final String COURSE_SYSTEM = "course-project-management";
    private static final String COURSE_AUDIT_VIEW = "AV";
    private static final Map<String, String> API_PERMISSION_CODES = Map.ofEntries(
            Map.entry("listTerms", "CV"),
            Map.entry("createTerm", "TC"),
            Map.entry("updateTerm", "TE"),
            Map.entry("deleteTerm", "TD"),
            Map.entry("setCurrentTerm", "TCM"),
            Map.entry("cancelCurrentTerm", "TCM"),
            Map.entry("getCourse", "CV"),
            Map.entry("createCourse", "CE"),
            Map.entry("submitCourseReview", "CR"),
            Map.entry("reviewCourse", "CR"),
            Map.entry("updateCourse", "CE"),
            Map.entry("deleteCourse", "CD"),
            Map.entry("copyCourse", "CC"),
            Map.entry("setCourseLeaders", "CTM"),
            Map.entry("listAdminCourses", "CV"),
            Map.entry("listTeachers", "TV"),
            Map.entry("createTeacher", "TEC"),
            Map.entry("updateTeacher", "TEE"),
            Map.entry("deleteTeacher", "TED"),
            Map.entry("queryAuditLogs", COURSE_AUDIT_VIEW),
            Map.entry("listAuditLogs", COURSE_AUDIT_VIEW)
    );

    private record DomainKey(long departmentId, long applicationId) {}

    private final IdentityCredentialRepository credentials;
    private final IdentitySessionRepository sessions;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final RoleInheritanceRepository roleInheritances;
    private final UserRoleRepository userRoles;
    private final AuditEventRepository auditEvents;
    private final OrganizationUseCase organization;
    private final AuditUseCase audit;
    private final PasswordEncoder passwordEncoder;
    private final IdentityTokenService tokenService;

    public StructuredIdentityService(
            IdentityCredentialRepository credentials,
            IdentitySessionRepository sessions,
            UserRepository users,
            DepartmentRepository departments,
            MembershipRepository memberships,
            RoleRepository roles,
            RolePermissionRepository rolePermissions,
            RoleInheritanceRepository roleInheritances,
            UserRoleRepository userRoles,
            AuditEventRepository auditEvents,
            OrganizationUseCase organization,
            AuditUseCase audit,
            PasswordEncoder passwordEncoder,
            IdentityTokenService tokenService
    ) {
        this.credentials = credentials;
        this.sessions = sessions;
        this.users = users;
        this.departments = departments;
        this.memberships = memberships;
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.roleInheritances = roleInheritances;
        this.userRoles = userRoles;
        this.auditEvents = auditEvents;
        this.organization = organization;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Override
    @Transactional
    public IdentityLoginResult login(String username, String password, List<String> systems) {
        if (systems == null || systems.isEmpty() || !systems.contains(COURSE_SYSTEM)) {
            throw ApiException.bad("systems 必须包含 course-project-management");
        }
        IdentityCredentialRepository.ActiveCredential credential = credentials.findActiveByUsername(username)
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHORIZED", "账号或密码错误。"));
        if (!passwordEncoder.matches(password, credential.getPasswordHash())) {
            throw ApiException.unauthorized("UNAUTHORIZED", "账号或密码错误。");
        }
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String token = tokenService.issue(credential.getUsername(), systems, issuedAt);
        IdentityTokenService.Claims claims = tokenService.verify(token, COURSE_SYSTEM);
        if (!credentials.createSessionIfVerified(Credentials.hash(token), credential.getUserId(),
                credential.getUsername(), credential.getPasswordHash(), COURSE_SYSTEM, claims.expiresAt())) {
            throw ApiException.unauthorized("UNAUTHORIZED", "账号或密码错误。");
        }
        return new IdentityLoginResult(token, "Bearer", claims.expiresAt().getEpochSecond() - issuedAt.getEpochSecond());
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityPageResult<CourseAuditEventResult> auditLogs(String authorization, String actor, Long actorId, String target,
                                                                OffsetDateTime from, OffsetDateTime to, int page, int pageSize) {
        UserRepository.IdentityUser user = authenticatedUser(authorization);
        List<Long> allowedDepts = permissionDepartments(user.getId(), COURSE_SYSTEM, COURSE_AUDIT_VIEW);
        if (allowedDepts.isEmpty()) {
            throw ApiException.forbidden("你没有查看课程日志的权限。");
        }
        Page<AuditEventRepositoryCustom.CourseAuditRow> events = auditEvents.findCourseAuditPage(COURSE_SYSTEM, allowedDepts,
                actor, actorId, target, from == null ? null : from.toInstant(), to == null ? null : to.toInstant(), page, pageSize);
        List<Long> actorIds = events.getContent().stream().map(AuditEventRepositoryCustom.CourseAuditRow::actorId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, List<String>> actorRoles = auditActorRoles(actorIds, COURSE_SYSTEM, allowedDepts);
        List<CourseAuditEventResult> items = events.getContent().stream().map(row -> auditItem(row, actorRoles)).toList();
        return new IdentityPageResult<>(page, pageSize, events.getTotalElements(), items);
    }

    private static CourseAuditEventResult auditItem(AuditEventRepositoryCustom.CourseAuditRow row, Map<Long, List<String>> roles) {
        Long id = row.actorId();
        CourseAuditActorResult actor = new CourseAuditActorResult(
                id,
                id == null ? row.actorNo() : row.actorName(),
                id == null ? List.of() : roles.getOrDefault(id, List.of()),
                id == null);
        return new CourseAuditEventResult(row.id(), row.at().toString(), actor, row.type(), row.target());
    }

    @Override
    @Transactional
    public void logout(String authorization) {
        String token = bearerToken(authorization);
        tokenService.verify(token, COURSE_SYSTEM);
        sessions.revokeByTokenHash(Credentials.hash(token), Instant.now());
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityCurrentUserResult currentUser(String authorization) {
        IdentityTokenService.Claims claims = authenticated(authorization);
        UserRepository.IdentityUser user = users.findActiveIdentityUserByExternalId(claims.subject())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHORIZED", "请先登录。"));
        List<String> roleCodes = effectiveRoleCodes(user.getId(), COURSE_SYSTEM).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
        List<String> permissions = effectivePermissionCodes(user.getId(), COURSE_SYSTEM);
        return new IdentityCurrentUserResult(user.getExternalId(), roleCodes, permissions, user.getName(), user.getExternalId());
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityPageResult<IdentityDepartmentResult> departments(String authorization, String keyword, Long parentId,
                                                                    int page, int pageSize) {
        UserRepository.IdentityUser user = authenticatedUser(authorization);
        requirePermission(user.getId(), "ORG_VIEW");
        List<IdentityDepartmentResult> items = departments.findIdentityDepartments(keyword, parentId, page, pageSize).stream()
                .map(row -> new IdentityDepartmentResult(row.id(), row.code(), row.name(), row.parentId(), row.hasChildren()))
                .toList();
        return new IdentityPageResult<>(page, pageSize, departments.countIdentityDepartments(keyword, parentId), items);
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityPageResult<IdentityDepartmentUserResult> departmentUsers(String authorization, long departmentId,
                                                                            String keyword, int page, int pageSize) {
        UserRepository.IdentityUser user = authenticatedUser(authorization);
        requirePermission(user.getId(), "ORG_VIEW");
        if (!departments.existsById(departmentId)) {
            throw ApiException.missing("IDENTITY_DEPARTMENT_NOT_FOUND", "统一身份认证系统中没有找到该部门。");
        }
        List<IdentityDepartmentUserResult> items = memberships.findIdentityDepartmentUsers(departmentId, keyword, page, pageSize).stream()
                .map(row -> new IdentityDepartmentUserResult(row.userId(), row.no(), row.name(), row.departmentId(), row.departmentName()))
                .toList();
        return new IdentityPageResult<>(page, pageSize, memberships.countIdentityDepartmentUsers(departmentId, keyword), items);
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityPermissionCheckResult checkPermission(String authorization, String permission) {
        UserRepository.IdentityUser user = authenticatedUser(authorization);
        return new IdentityPermissionCheckResult(permission, effectivePermissionCodes(user.getId(), COURSE_SYSTEM).contains(permission));
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityApiPermissionCheckResult checkApiPermission(String authorization, String operationId) {
        UserRepository.IdentityUser user = authenticatedUser(authorization);
        String permission = API_PERMISSION_CODES.get(operationId);
        if (permission == null) {
            throw ApiException.missing("API_OPERATION_NOT_FOUND", "没有找到对应的 API 操作。");
        }
        return new IdentityApiPermissionCheckResult(operationId, effectivePermissionCodes(user.getId(), COURSE_SYSTEM).contains(permission));
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityUserLookupResult lookupUser(String authorization, String userId) {
        UserRepository.IdentityUser current = authenticatedUser(authorization);
        requirePermission(current.getId(), "TEC");
        UserRepository.IdentityUser user = users.findActiveIdentityUserByExternalId(userId)
                .orElseThrow(() -> ApiException.missing("IDENTITY_USER_NOT_FOUND", "统一身份认证系统中没有找到该用户。"));
        return new IdentityUserLookupResult(user.getExternalId(), user.getExternalId(), user.getName());
    }

    @Override
    @Transactional
    public long provisionUser(SaveUserCommand body, String password, String actor) {
        Long existingId = users.findIdByExternalId(body.externalId()).orElse(null);
        long userId = organization.user(body, existingId, actor);
        savePassword(userId, body.externalId(), password, actor);
        return userId;
    }

    @Override
    @Transactional
    public long createUser(CreateUserWithPasswordCommand body, AdminPrincipal principal) {
        if (body.identityPassword() != null && !principal.canUse(AdminFunction.USER_CREDENTIALS)) {
            throw ApiException.forbidden("当前账号没有管理用户登录凭据功能");
        }
        long userId = organization.user(body.profile(), null, principal.actor(), principal.visibleDepartments());
        if (body.identityPassword() != null) {
            savePassword(userId, body.externalId(), body.identityPassword(), principal.actor());
        }
        return userId;
    }

    @Override
    @Transactional
    public void setUserPassword(long userId, String password, AdminPrincipal principal) {
        String username = organization.lockOwnedUser(userId, principal.visibleDepartments());
        savePassword(userId, username, password, principal.actor());
    }

    private void savePassword(long userId, String username, String password, String actor) {
        if (password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.bad("统一身份密码不能为空，且 UTF-8 长度不能超过 72 字节");
        }
        credentials.upsertCredential(userId, username, passwordEncoder.encode(password));
        sessions.revokeUserSessions(userId);
        audit.audit(actor, "USER_IDENTITY_PASSWORD_SET", username, null, null, "统一身份会话已结束");
    }

    private IdentityTokenService.Claims authenticated(String authorization) {
        String token = bearerToken(authorization);
        IdentityTokenService.Claims claims = tokenService.verify(token, COURSE_SYSTEM);
        if (!sessions.hasActiveSession(Credentials.hash(token), Instant.now())) {
            throw ApiException.unauthorized("UNAUTHORIZED", "请先登录或令牌已失效。");
        }
        return claims;
    }

    private UserRepository.IdentityUser authenticatedUser(String authorization) {
        IdentityTokenService.Claims claims = authenticated(authorization);
        return users.findActiveIdentityUserByExternalId(claims.subject())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHORIZED", "请先登录。"));
    }

    private void requirePermission(long userId, String permission) {
        List<String> permissions = effectivePermissionCodes(userId, COURSE_SYSTEM);
        List<String> roleCodes = effectiveRoleCodes(userId, COURSE_SYSTEM);
        if (!permissions.contains(permission) && !roleCodes.contains("admin")) {
            throw ApiException.forbidden("你没有查询统一身份组织架构的权限。");
        }
    }

    private List<Long> effectiveRoleIds(long userId, String systemCode) {
        List<UserRoleRepository.DirectRoleDomain> direct = userRoles.findActiveIdentityRoles(userId, systemCode);
        if (direct.isEmpty()) {
            return List.of();
        }
        Map<DomainKey, List<Long>> grouped = new LinkedHashMap<>();
        for (UserRoleRepository.DirectRoleDomain row : direct) {
            grouped.computeIfAbsent(new DomainKey(row.getDepartmentId(), row.getApplicationId()), k -> new ArrayList<>())
                    .add(row.getRoleId());
        }
        Set<Long> result = new LinkedHashSet<>();
        for (Map.Entry<DomainKey, List<Long>> entry : grouped.entrySet()) {
            result.addAll(roleInheritances.expandActiveRoleIds(
                    entry.getValue(), entry.getKey().departmentId(), entry.getKey().applicationId()));
        }
        return new ArrayList<>(result);
    }

    private List<String> effectiveRoleCodes(long userId, String systemCode) {
        List<Long> effective = effectiveRoleIds(userId, systemCode);
        return effective.isEmpty() ? List.of() : roles.findActiveCodesByIdIn(effective);
    }

    private List<String> effectivePermissionCodes(long userId, String systemCode) {
        List<Long> effective = effectiveRoleIds(userId, systemCode);
        return effective.isEmpty() ? List.of() : rolePermissions.findActivePermissionCodesByRoleIds(effective);
    }

    private List<Long> permissionDepartments(long userId, String systemCode, String permissionCode) {
        List<Long> effective = effectiveRoleIds(userId, systemCode);
        return effective.isEmpty() ? List.of() : rolePermissions.findDepartmentIdsWithPermission(effective, permissionCode);
    }

    private Map<Long, List<String>> auditActorRoles(List<Long> actorIds, String systemCode, List<Long> departmentIds) {
        if (actorIds.isEmpty() || departmentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> result = new HashMap<>();
        for (UserRoleRepository.ActorRoleCode row : userRoles.findAuditActorRoles(actorIds, systemCode, departmentIds)) {
            result.computeIfAbsent(row.getUserId(), ignored -> new ArrayList<>())
                    .add(row.getCode().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw ApiException.unauthorized("UNAUTHORIZED", "请先登录。");
        }
        return authorization.substring(7).trim();
    }
}
