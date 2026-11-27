package com.melonhub.rbac.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.gateway.SpringPlatformGatewaysAdapter;
import com.melonhub.rbac.infrastructure.identity.IdentityTokenService;
import com.melonhub.rbac.infrastructure.persistence.gateway.JpaGovernanceRepositoryAdapter;
import com.melonhub.rbac.infrastructure.persistence.gateway.JpaIdentityRepositoryAdapter;
import com.melonhub.rbac.infrastructure.persistence.gateway.JpaPermissionContextRepositoryAdapter;
import com.melonhub.rbac.infrastructure.persistence.gateway.JpaRbacRepositoryAdapter;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessRequestRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessReviewItemRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessReviewRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminAccountRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationKeyRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditSealRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ContextRoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.IdentityCredentialRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.IdentitySessionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.MembershipRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionContextRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleInheritanceRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RolePermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.service.ddd.application.console.DddConsoleService;
import com.melonhub.rbac.service.ddd.application.context.DddContextService;
import com.melonhub.rbac.service.ddd.application.domainrbac.DddDomainRbacService;
import com.melonhub.rbac.service.ddd.application.organization.DddOrganizationService;
import com.melonhub.rbac.service.ddd.domain.model.AccessRequestAggregate;
import com.melonhub.rbac.service.ddd.domain.model.AccessReviewAggregate;
import com.melonhub.rbac.service.ddd.domain.model.AdminCredentialAccount;
import com.melonhub.rbac.service.ddd.domain.model.AdminHierarchy;
import com.melonhub.rbac.service.ddd.domain.model.AdminRoleAggregate;
import com.melonhub.rbac.service.ddd.domain.model.AuditHashChainAggregate;
import com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate;
import com.melonhub.rbac.service.ddd.domain.model.WebhookEndpointAggregate;
import com.melonhub.rbac.infrastructure.persistence.gateway.JpaConsoleMetricsAdapter;
import com.melonhub.rbac.service.ddd.domain.repository.ConsoleMetricsPort;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.DomainEventPublisher;
import com.melonhub.rbac.service.ddd.domain.value.AdminScope;
import com.melonhub.rbac.service.ddd.domain.value.AuditCanonicalizer;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.GrantDuration;
import com.melonhub.rbac.service.ddd.domain.value.IsolationDomain;
import com.melonhub.rbac.service.ddd.domain.value.WebhookSignature;
import com.melonhub.rbac.service.port.audit.AuditSealUseCase;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.console.ConsoleUseCase;
import com.melonhub.rbac.service.port.context.ContextUseCase;
import com.melonhub.rbac.service.port.context.IssueContextCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacUseCase;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveUserCommand;
import com.melonhub.rbac.service.port.organization.OrganizationUseCase;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import com.melonhub.rbac.service.structured.audit.StructuredAuditSealService;
import com.melonhub.rbac.service.structured.console.StructuredConsoleService;
import com.melonhub.rbac.service.structured.context.StructuredContextService;
import com.melonhub.rbac.service.structured.domainrbac.StructuredDomainRbacService;
import com.melonhub.rbac.service.structured.organization.StructuredOrganizationService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * 纯 DDD 领域聚合根行为守护测试与结构化/DDD 双策略等价性测试（覆盖 R33）。
 */
class R33_DddDomainAndDualStrategyParityUnitTest {

    // =========================================================================
    // 统一测试参数区（策略、固定时钟、样本 ID、编码、预期错误码与令牌）
    // =========================================================================
    private static final String STRATEGY_DDD = "ddd";
    private static final Instant FIXED_NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final long AUDIT_ID = 42L;
    private static final long AUDIT_EPOCH_SECONDS = 1732440000L;
    private static final long V2_SWITCH_SEQ = 5L;
    private static final long USER_ID = 10L;
    private static final long DEPT_KEEP_ID = 1L;
    private static final long DEPT_REMOVE_ID = 2L;
    private static final long APP_ID = 5L;
    private static final long ROLE_ID = 88L;
    private static final long MISSING_ROLE_ID = 99L;
    private static final long EXPIRED_CONTEXT_ROLE_ID = 100L;
    private static final String USER_NAME = "张三";
    private static final String USER_EXT_ID = "u001";
    private static final String ROLE_CODE = "editor";
    private static final String ACTOR_ADMIN = "admin";
    private static final String CONTEXT_TOKEN = "ctx_token_unit_test";
    private static final String IDENTITY_JWT_SECRET = "unit-test-secret-unit-test-secret-32";
    private static final String IDENTITY_ISSUER = "https://identity.test";
    private static final String ERR_DEPARTMENT_MISMATCH = "DEPARTMENT_MISMATCH";

    @Test
    @DisplayName("DDD-01 审计链哈希规范化（v1/v2）与结构化实现逐字节一致，且可检测断链、篡改与尾缺")
    void auditHashChainCanonicalizerMatchesStructuredAndDetectsAllAnomalies() {
        var row = new StructuredAuditSealService.ChainRow(
                AUDIT_ID, ACTOR_ADMIN, "ROLE_UPDATED", "ops|role", 3L, null, "detail:value", "127.0.0.1",
                AUDIT_EPOCH_SECONDS, null, null, null);

        String v1Structured = StructuredAuditSealService.chainHash(StructuredAuditSealService.GENESIS, 1L, row, V2_SWITCH_SEQ);
        String v1Ddd = AuditCanonicalizer.chainHash(AuditCanonicalizer.GENESIS, 1L, V2_SWITCH_SEQ,
                AUDIT_ID, ACTOR_ADMIN, "ROLE_UPDATED", "ops|role", 3L, null, "detail:value", "127.0.0.1", AUDIT_EPOCH_SECONDS);
        assertThat(v1Ddd).isEqualTo(v1Structured);

        String v2Structured = StructuredAuditSealService.chainHash(v1Structured, V2_SWITCH_SEQ, row, V2_SWITCH_SEQ);
        String v2Ddd = AuditCanonicalizer.chainHash(v1Ddd, V2_SWITCH_SEQ, V2_SWITCH_SEQ,
                AUDIT_ID, ACTOR_ADMIN, "ROLE_UPDATED", "ops|role", 3L, null, "detail:value", "127.0.0.1", AUDIT_EPOCH_SECONDS);
        assertThat(v2Ddd).isEqualTo(v2Structured);

        var seal = new AuditHashChainAggregate.SealMetadata(AUDIT_ID, 1L, v1Ddd, 0L, AuditCanonicalizer.GENESIS, V2_SWITCH_SEQ);
        var aggregate = new AuditHashChainAggregate(seal);
        var goodEvent = new AuditHashChainAggregate.AuditEventData(
                AUDIT_ID, ACTOR_ADMIN, "ROLE_UPDATED", "ops|role", 3L, null, "detail:value", "127.0.0.1", AUDIT_EPOCH_SECONDS,
                1L, AuditCanonicalizer.GENESIS, v1Ddd);
        assertThat(aggregate.verifySegment(List.of(goodEvent), null, 100).ok()).isTrue();

        var modified = new AuditHashChainAggregate.AuditEventData(
                AUDIT_ID, "attacker", "ROLE_UPDATED", "ops|role", 3L, null, "detail:value", "127.0.0.1", AUDIT_EPOCH_SECONDS,
                1L, AuditCanonicalizer.GENESIS, v1Ddd);
        assertThat(aggregate.verifySegment(List.of(modified), null, 100).problem()).isEqualTo("MODIFIED");

        var brokenLink = new AuditHashChainAggregate.AuditEventData(
                AUDIT_ID, ACTOR_ADMIN, "ROLE_UPDATED", "ops|role", 3L, null, "detail:value", "127.0.0.1", AUDIT_EPOCH_SECONDS,
                1L, "f".repeat(64), v1Ddd);
        assertThat(aggregate.verifySegment(List.of(brokenLink), null, 100).problem()).isEqualTo("LINK_BROKEN");

        assertThat(aggregate.verifySegment(List.of(), null, 100).problem()).isEqualTo("TAIL_MISSING");
    }

    @Test
    @DisplayName("DDD-02 管理员角色、凭据登录状态机、审批、复核、删部门/软件与 Webhook 聚合根守护全部不变量")
    void domainAggregatesEnforceCoreInvariants() {
        var builtIn = new AdminRoleAggregate("SUPER_ADMIN", "超管", "", true, Set.of("org.view"));
        assertThatThrownBy(() -> builtIn.updateCustom("改名", "", List.of("org.view"),
                Set.of("org.view"), Set.of("org.view"), Set.of("user.manage")))
                .isInstanceOf(DomainException.class).extracting("kind").isEqualTo(DomainException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> builtIn.assertDeletable(0))
                .isInstanceOf(DomainException.class).extracting("kind").isEqualTo(DomainException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> AdminRoleAggregate.createCustom("CUSTOM_OPS", "自定义", "",
                List.of("org.view", "user.manage"), Set.of("org.view", "user.manage"),
                Set.of("org.view", "user.manage"), Set.of("user.manage")))
                .isInstanceOf(DomainException.class).extracting("kind").isEqualTo(DomainException.Kind.FORBIDDEN);

        var beforeRole = new AdminRoleAggregate("CUSTOM_OPS", "自定义", "", false, Set.of("org.view", "audit.view"));
        var afterRole = beforeRole.updateCustom("自定义", "", List.of("org.view", "domain.view"),
                Set.of("org.view", "audit.view", "domain.view"), Set.of("org.view", "audit.view", "domain.view"), Set.of());
        assertThat(afterRole.auditUpdateDiffFrom(beforeRole)).isEqualTo("新增功能 domain.view；移除功能 audit.view");

        assertThatThrownBy(() -> DomainAuthorizationAggregate.assertDepartmentDeletable(
                new DomainAuthorizationAggregate.DepartmentUsage(2, 0, 1, 0)))
                .isInstanceOf(DomainException.class).hasMessageContaining("2 名成员").hasMessageContaining("1 个权限");
        assertThatThrownBy(() -> DomainAuthorizationAggregate.assertApplicationDeletable(
                new DomainAuthorizationAggregate.ApplicationUsage(3, 0)))
                .isInstanceOf(DomainException.class).hasMessageContaining("3 个角色");

        var account = new AdminCredentialAccount(1L, "ops", "Ops", "ADMIN", true, "hash", false,
                "SECRET", 100L, 1L, "MANAGER", true, AdminScope.unrestricted());
        assertThat(account.evaluateLogin(false, null, null)).isEqualTo(AdminCredentialAccount.LoginCheck.PASSWORD_MISMATCH);
        assertThat(account.evaluateLogin(true, "", null)).isEqualTo(AdminCredentialAccount.LoginCheck.MFA_CODE_MISSING);
        assertThat(account.evaluateLogin(true, "123456", 100L)).isEqualTo(AdminCredentialAccount.LoginCheck.MFA_INVALID);
        assertThat(account.evaluateLogin(true, "123456", 101L)).isEqualTo(AdminCredentialAccount.LoginCheck.READY_CONSUME_MFA);

        var req = new AccessRequestAggregate(9L, "PENDING", 2L, 3L, 4L, 5L, true, USER_EXT_ID, "dev", 7);
        assertThatThrownBy(() -> req.planApproval(true, 10, "ok", FIXED_NOW))
                .isInstanceOf(DomainException.class).extracting("kind").isEqualTo(DomainException.Kind.BAD_REQUEST);
        var plan = req.planApproval(true, null, "ok", FIXED_NOW);
        assertThat(plan.expiresAt()).isNull();
        assertThat(plan.auditDetail()).contains("永久授权");

        var lockOrder = AccessReviewAggregate.orderedUsersToLock(List.of(
                new AccessReviewAggregate.RevocationCandidate(1L, 9L, 1L, 1L, "g1"),
                new AccessReviewAggregate.RevocationCandidate(2L, 3L, 2L, 1L, "g2")));
        assertThat(lockOrder).containsExactly(3L, 9L);
        assertThat(AccessReviewAggregate.closeSummary(2, 1)).contains("回收 2 条授权", "1 条在复核期间已变更或移除");

        var disabledHook = new WebhookEndpointAggregate(1L, 2L, "h", "https://example.com", false, List.of("*"));
        assertThatThrownBy(disabledHook::assertTestAllowed)
                .isInstanceOf(DomainException.class).extracting("kind").isEqualTo(DomainException.Kind.CONFLICT);
        assertThat(WebhookSignature.backoffMinutes(1)).isEqualTo(1);
        assertThat(WebhookSignature.backoffMinutes(4)).isEqualTo(8);
        assertThat(WebhookSignature.backoffMinutes(8)).isEqualTo(60);
        assertThat(WebhookEndpointAggregate.decideOnHttpStatus(8, 8, 500).type())
                .isEqualTo(WebhookEndpointAggregate.DeliveryOutcomeType.FAILED);
    }

    @Test
    @DisplayName("DDD-03 三层角色有效期优先级与并发晚到成员识别")
    void domainAuthorizationAggregateHandlesExpiriesAndLateMembers() {
        var domain = new DomainAuthorizationAggregate(new IsolationDomain(1L, 2L));
        Instant keptExpiry = FIXED_NOW.plus(15, ChronoUnit.DAYS);
        Instant overrideExpiry = FIXED_NOW.plus(30, ChronoUnit.DAYS);
        var plan = domain.planUserRoleReplacement(
                List.of(10L, 20L, 30L),
                Map.of(10L, keptExpiry, 20L, keptExpiry, 40L, keptExpiry),
                null,
                List.of(new GrantDuration.RoleExpiryOverride(20L, overrideExpiry)),
                FIXED_NOW);
        assertThat(plan.removedRoleIds()).containsExactly(40L);
        assertThat(plan.expiryChanges()).isEqualTo(1);
        assertThat(plan.grantsToApply()).extracting(DomainAuthorizationAggregate.GrantChange::roleId)
                .containsExactly(20L, 30L);

        var latePlan = domain.planRoleMemberReplacement(Set.of(1L, 2L), Set.of(1L, 2L), Set.of(1L, 99L));
        assertThat(latePlan.requiresRetry()).isTrue();
        assertThat(latePlan.lateMembers()).containsExactly(99L);
    }

    @Test
    @DisplayName("DDD-04 管理员层级聚合根拒绝自删与软件管理员错误作用域轴")
    void adminHierarchyProtectsCriticalAccountInvariants() {
        var root = new AdminHierarchy.AdminNode(1L, "root", null, "MANAGER", "SUPER_ADMIN", true, AdminScope.unrestricted());
        var tree = new AdminHierarchy(List.of(root), Map.of(
                "SUPER_ADMIN", Set.of("account.manage", "role.manage"),
                "APP_ADMIN", Set.of("domain.view")));
        assertThatThrownBy(() -> tree.validateDelete(1L, 1L))
                .isInstanceOf(DomainException.class).extracting("kind").isEqualTo(DomainException.Kind.BAD_REQUEST);
        var badAppAdmin = new AdminHierarchy.AdminNode(0L, "app1", 1L, "MANAGER", "APP_ADMIN", true,
                new AdminScope(false, Set.of(1L), false, Set.of(2L)));
        assertThatThrownBy(() -> tree.validateCreate(1L, badAppAdmin))
                .isInstanceOf(DomainException.class).extracting("kind").isEqualTo(DomainException.Kind.CONFLICT);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"structured", "ddd"})
    @DisplayName("PARITY-01 移出用户部门时双策略同步清除遗留域角色、撤销域上下文并发出事件与审计")
    void removingUserDepartmentCleansStaleRoleGrantsIdentically(String strategy) {
        var departments = mock(DepartmentRepository.class);
        var applications = mock(ApplicationRepository.class);
        var applicationKeys = mock(ApplicationKeyRepository.class);
        var users = mock(UserRepository.class);
        var memberships = mock(MembershipRepository.class);
        var roles = mock(RoleRepository.class);
        var permissions = mock(PermissionRepository.class);
        var rolePermissions = mock(RolePermissionRepository.class);
        var roleInheritances = mock(RoleInheritanceRepository.class);
        var userRoles = mock(UserRoleRepository.class);
        var contexts = mock(PermissionContextRepository.class);
        var contextRoles = mock(ContextRoleRepository.class);
        var accessRequests = mock(AccessRequestRepository.class);
        var accessReviews = mock(AccessReviewRepository.class);
        var reviewItems = mock(AccessReviewItemRepository.class);
        var adminAccounts = mock(AdminAccountRepository.class);
        var identityCredentials = mock(IdentityCredentialRepository.class);
        var identitySessions = mock(IdentitySessionRepository.class);
        var auditEvents = mock(AuditEventRepository.class);
        var auditSeals = mock(AuditSealRepository.class);
        var cache = mock(PermissionCache.class);
        var audit = mock(AuditUseCase.class);
        var webhookUseCase = mock(WebhookUseCase.class);
        var eventPublisher = mock(DomainEventPublisher.class);

        var userSummary = mock(UserRepository.UserSummary.class);
        when(userSummary.getId()).thenReturn(USER_ID);
        when(userSummary.getName()).thenReturn(USER_NAME);
        when(userSummary.getExternalId()).thenReturn(USER_EXT_ID);
        when(userSummary.getEmail()).thenReturn("");
        when(userSummary.isEnabled()).thenReturn(true);
        when(userSummary.getCreatedAt()).thenReturn(FIXED_NOW);
        var staleAssignment = mock(UserRoleRepository.DepartmentAssignment.class);
        when(staleAssignment.getDepartmentId()).thenReturn(DEPT_REMOVE_ID);
        when(staleAssignment.getApplicationId()).thenReturn(APP_ID);
        when(staleAssignment.getRoleId()).thenReturn(ROLE_ID);
        when(staleAssignment.getRoleCode()).thenReturn(ROLE_CODE);

        when(departments.existsById(DEPT_KEEP_ID)).thenReturn(true);
        when(users.lockExternalIdById(USER_ID)).thenReturn(Optional.of(USER_EXT_ID));
        when(users.findSummaryById(USER_ID)).thenReturn(Optional.of(userSummary));
        when(memberships.findLockedDepartmentIds(USER_ID)).thenReturn(List.of(DEPT_KEEP_ID, DEPT_REMOVE_ID));
        when(userRoles.findAssignmentsInDepartments(USER_ID, List.of(DEPT_REMOVE_ID))).thenReturn(List.of(staleAssignment));

        OrganizationUseCase service;
        if (STRATEGY_DDD.equals(strategy)) {
            var gateway = gateway(cache);
            service = new DddOrganizationService(
                    new JpaRbacRepositoryAdapter(departments, applications, applicationKeys, users, memberships, roles,
                            permissions, rolePermissions, roleInheritances, userRoles, contexts, adminAccounts, auditEvents, auditSeals),
                    new JpaPermissionContextRepositoryAdapter(contexts, contextRoles, users, applications, departments,
                            memberships, roles, rolePermissions, roleInheritances, userRoles),
                    new JpaGovernanceRepositoryAdapter(accessRequests, accessReviews, reviewItems, userRoles,
                            users, departments, applications, roles),
                    new JpaIdentityRepositoryAdapter(identityCredentials, identitySessions, users, departments,
                            memberships, roles, rolePermissions, roleInheritances, userRoles, auditEvents),
                    gateway, audit, eventPublisher);
        } else {
            service = new StructuredOrganizationService(departments, users, memberships, roles, permissions,
                    rolePermissions, roleInheritances, userRoles, contexts, contextRoles, accessRequests,
                    adminAccounts, identityCredentials, identitySessions, cache, audit, webhookUseCase);
        }

        long id = service.user(new SaveUserCommand(USER_NAME, USER_EXT_ID, "", List.of(DEPT_KEEP_ID)), USER_ID, ACTOR_ADMIN, null);
        assertThat(id).isEqualTo(USER_ID);
        verify(userRoles).deleteInDepartments(USER_ID, List.of(DEPT_REMOVE_ID));
        verify(memberships).deleteByUserIdAndDepartmentId(USER_ID, DEPT_REMOVE_ID);
        verify(contexts).revokeDomain(USER_ID, DEPT_REMOVE_ID, APP_ID);
        verify(cache).touchUser(USER_ID);
        Map<String, Object> expectedPayload = Map.of(
                "userId", USER_ID, "externalId", USER_EXT_ID, "departmentId", DEPT_REMOVE_ID, "removed", 1);
        if (STRATEGY_DDD.equals(strategy)) {
            verify(eventPublisher).emit(eq(APP_ID), eq("user.roles.changed"), eq(expectedPayload));
        } else {
            verify(webhookUseCase).emit(eq(APP_ID), eq("user.roles.changed"), eq(expectedPayload));
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"structured", "ddd"})
    @DisplayName("PARITY-02 公开健康检查字段与 scoped 隔离域 403 契约双策略一致")
    void healthContractAndScopedForbiddenMatchIdentically(String strategy) {
        var auditSeals = mock(AuditSealRepository.class);
        var roles = mock(RoleRepository.class);
        var cache = mock(PermissionCache.class);
        when(auditSeals.isDatabaseHealthy()).thenReturn(true, false);
        when(cache.health()).thenReturn("UP");
        when(roles.existsByIdAndDepartmentIdAndApplicationId(MISSING_ROLE_ID, DEPT_KEEP_ID, DEPT_REMOVE_ID)).thenReturn(false);

        ConsoleUseCase console;
        DomainRbacUseCase domainRbac;
        if (STRATEGY_DDD.equals(strategy)) {
            var gateway = gateway(cache);
            var rbacRepo = new JpaRbacRepositoryAdapter(mock(DepartmentRepository.class), mock(ApplicationRepository.class),
                    mock(ApplicationKeyRepository.class), mock(UserRepository.class), mock(MembershipRepository.class), roles,
                    mock(PermissionRepository.class), mock(RolePermissionRepository.class), mock(RoleInheritanceRepository.class),
                    mock(UserRoleRepository.class), mock(PermissionContextRepository.class), mock(AdminAccountRepository.class),
                    mock(AuditEventRepository.class), auditSeals);
            var metrics = new JpaConsoleMetricsAdapter(mock(UserRepository.class), mock(DepartmentRepository.class),
                    mock(ApplicationRepository.class), roles, mock(PermissionRepository.class), mock(UserRoleRepository.class),
                    mock(PermissionContextRepository.class), mock(AccessRequestRepository.class), mock(AccessReviewRepository.class),
                    mock(WebhookDeliveryRepository.class), mock(AuditEventRepository.class), auditSeals, cache);
            console = new DddConsoleService(metrics);
            domainRbac = new DddDomainRbacService(rbacRepo, mock(JpaPermissionContextRepositoryAdapter.class),
                    mock(JpaGovernanceRepositoryAdapter.class), gateway, mock(AuditUseCase.class),
                    mock(DomainEventPublisher.class), gateway);
        } else {
            console = new StructuredConsoleService(mock(UserRepository.class), mock(DepartmentRepository.class),
                    mock(ApplicationRepository.class), roles, mock(PermissionRepository.class),
                    mock(UserRoleRepository.class), mock(PermissionContextRepository.class), mock(AccessRequestRepository.class),
                    mock(AccessReviewRepository.class), mock(WebhookDeliveryRepository.class), mock(AuditEventRepository.class),
                    auditSeals, cache);
            domainRbac = new StructuredDomainRbacService(mock(DepartmentRepository.class), mock(ApplicationRepository.class),
                    mock(ApplicationKeyRepository.class), mock(UserRepository.class), mock(MembershipRepository.class),
                    roles, mock(PermissionRepository.class), mock(RolePermissionRepository.class),
                    mock(RoleInheritanceRepository.class), mock(UserRoleRepository.class),
                    mock(PermissionContextRepository.class), mock(AccessRequestRepository.class),
                    mock(AuditEventRepository.class), cache, mock(AuditUseCase.class), mock(WebhookUseCase.class),
                    mock(PlatformTransactionManager.class));
        }

        var upHealth = console.health();
        assertThat(upHealth.status()).isEqualTo("UP");
        assertThat(upHealth.database()).isEqualTo("UP");
        assertThat(upHealth.cache()).isEqualTo("UP");
        assertThat(upHealth.model()).isEqualTo("RBAC1");

        var downHealth = console.health();
        assertThat(downHealth.status()).isEqualTo("DOWN");
        assertThat(downHealth.database()).isEqualTo("DOWN");

        assertThatThrownBy(() -> domainRbac.roleStatus(MISSING_ROLE_ID, DEPT_KEEP_ID, DEPT_REMOVE_ID, false, ACTOR_ADMIN))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PARITY-03 双策略四个短参数写重载均直接声明 @Transactional，防止代理自调用丢事务")
    void bothStrategiesDeclareTransactionalOnShortWriteOverloads() throws Exception {
        for (Class<?> orgClass : List.of(StructuredOrganizationService.class, DddOrganizationService.class)) {
            assertThat(orgClass.getDeclaredMethod("user", SaveUserCommand.class, Long.class, String.class)
                    .isAnnotationPresent(Transactional.class)).as(orgClass.getSimpleName() + ".user").isTrue();
            assertThat(orgClass.getDeclaredMethod("userStatus", long.class, boolean.class, String.class)
                    .isAnnotationPresent(Transactional.class)).as(orgClass.getSimpleName() + ".userStatus").isTrue();
        }
        for (Class<?> rbacClass : List.of(StructuredDomainRbacService.class, DddDomainRbacService.class)) {
            assertThat(rbacClass.getDeclaredMethod("userRoles", long.class, long.class, long.class, List.class, String.class)
                    .isAnnotationPresent(Transactional.class)).as(rbacClass.getSimpleName() + ".userRoles(5)").isTrue();
            assertThat(rbacClass.getDeclaredMethod("userRoles", long.class, long.class, long.class, List.class, Integer.class, String.class)
                    .isAnnotationPresent(Transactional.class)).as(rbacClass.getSimpleName() + ".userRoles(6)").isTrue();
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"structured", "ddd"})
    @DisplayName("PARITY-04 switchDepartment=false 时跨部门签发上下文双策略均返回 409 DEPARTMENT_MISMATCH")
    void contextIssueRejectsImplicitDepartmentSwitchIdentically(String strategy) {
        var contexts = mock(PermissionContextRepository.class);
        var contextRoles = mock(ContextRoleRepository.class);
        var users = mock(UserRepository.class);
        var applications = mock(ApplicationRepository.class);
        var departments = mock(DepartmentRepository.class);
        var memberships = mock(MembershipRepository.class);
        var roles = mock(RoleRepository.class);
        var rolePermissions = mock(RolePermissionRepository.class);
        var roleInheritances = mock(RoleInheritanceRepository.class);
        var userRoles = mock(UserRoleRepository.class);
        var domainRbac = mock(DomainRbacUseCase.class);
        var audit = mock(AuditUseCase.class);
        var cache = mock(PermissionCache.class);

        when(users.lockRowByExternalId(USER_EXT_ID)).thenReturn(Optional.of(
                new UserRepository.LockedUserRow(USER_ID, true, DEPT_KEEP_ID, 3L)));
        when(applications.existsByIdAndEnabledTrue(APP_ID)).thenReturn(true);
        when(memberships.existsByUserIdAndDepartmentId(USER_ID, DEPT_REMOVE_ID)).thenReturn(true);
        when(userRoles.findActiveAssignedRoleIds(USER_ID, DEPT_REMOVE_ID, APP_ID)).thenReturn(List.of(EXPIRED_CONTEXT_ROLE_ID));

        ContextUseCase service = contextService(strategy, contexts, contextRoles, users, applications, departments,
                memberships, roles, rolePermissions, roleInheritances, userRoles, domainRbac, audit, cache);

        assertThatThrownBy(() -> service.issueContext(APP_ID, new IssueContextCommand(USER_EXT_ID, DEPT_REMOVE_ID, null, false), "app"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo(ERR_DEPARTMENT_MISMATCH);
                });
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"structured", "ddd"})
    @DisplayName("PARITY-05 上下文激活角色到期或停用后不再展开继承权限（双策略等价）")
    void expiredOrDisabledContextRoleDoesNotLeakPermissions(String strategy) {
        var contexts = mock(PermissionContextRepository.class);
        var contextRoles = mock(ContextRoleRepository.class);
        var users = mock(UserRepository.class);
        var applications = mock(ApplicationRepository.class);
        var departments = mock(DepartmentRepository.class);
        var memberships = mock(MembershipRepository.class);
        var roles = mock(RoleRepository.class);
        var rolePermissions = mock(RolePermissionRepository.class);
        var roleInheritances = mock(RoleInheritanceRepository.class);
        var userRoles = mock(UserRoleRepository.class);
        var domainRbac = mock(DomainRbacUseCase.class);
        var audit = mock(AuditUseCase.class);
        var cache = mock(PermissionCache.class);

        String hash = Credentials.hash(CONTEXT_TOKEN);
        when(cache.context(hash)).thenReturn(new PermissionCache.Lookup(PermissionCache.Status.BYPASS, null, null, null));
        when(contexts.findStateById(hash)).thenReturn(Optional.of(new PermissionContextRepository.ContextState() {
            @Override public long getUserId() { return USER_ID; }
            @Override public Long getDepartmentId() { return DEPT_REMOVE_ID; }
            @Override public long getApplicationId() { return APP_ID; }
            @Override public long getContextVersion() { return 1L; }
            @Override public Instant getExpiresAt() { return Instant.now().plusSeconds(1800); }
            @Override public boolean isValid() { return true; }
        }));
        when(contextRoles.findRoleIdsByContextId(hash)).thenReturn(List.of(EXPIRED_CONTEXT_ROLE_ID));
        when(roles.findEffectiveRoles(USER_ID, List.of(EXPIRED_CONTEXT_ROLE_ID), DEPT_REMOVE_ID, APP_ID)).thenReturn(List.of());

        ContextUseCase service = contextService(strategy, contexts, contextRoles, users, applications, departments,
                memberships, roles, rolePermissions, roleInheritances, userRoles, domainRbac, audit, cache);

        var result = service.effective(APP_ID, CONTEXT_TOKEN);
        assertThat(result.roles()).isEmpty();
        assertThat(result.permissions()).isEmpty();
        verify(roleInheritances, never()).expandActiveRoleIds(any(), anyLong(), anyLong());
        verify(rolePermissions, never()).findActivePermissionCodesInDomain(any(), anyLong(), anyLong());
    }

    private static SpringPlatformGatewaysAdapter gateway(PermissionCache cache) {
        return new SpringPlatformGatewaysAdapter(cache, mock(PlatformTransactionManager.class),
                new BCryptPasswordEncoder(4),
                new IdentityTokenService(IDENTITY_JWT_SECRET, IDENTITY_ISSUER, 7200),
                new ObjectMapper());
    }

    private static ContextUseCase contextService(String strategy, PermissionContextRepository contexts,
                                                 ContextRoleRepository contextRoles, UserRepository users,
                                                 ApplicationRepository applications, DepartmentRepository departments,
                                                 MembershipRepository memberships, RoleRepository roles,
                                                 RolePermissionRepository rolePermissions, RoleInheritanceRepository roleInheritances,
                                                 UserRoleRepository userRoles, DomainRbacUseCase domainRbac,
                                                 AuditUseCase audit, PermissionCache cache) {
        if (STRATEGY_DDD.equals(strategy)) {
            var gateway = gateway(cache);
            return new DddContextService(
                    new JpaPermissionContextRepositoryAdapter(contexts, contextRoles, users, applications,
                            departments, memberships, roles, rolePermissions, roleInheritances, userRoles),
                    domainRbac, audit, gateway, gateway, gateway, gateway);
        }
        return new StructuredContextService(contexts, contextRoles, users, applications, departments,
                memberships, roles, rolePermissions, roleInheritances, userRoles, domainRbac, audit, cache,
                new ObjectMapper(), mock(PlatformTransactionManager.class));
    }
}
