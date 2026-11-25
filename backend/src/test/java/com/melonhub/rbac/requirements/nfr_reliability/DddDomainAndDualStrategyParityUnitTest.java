package com.melonhub.rbac.requirements.nfr_reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.cache.PermissionCache;
import com.melonhub.rbac.dao.AuditDao;
import com.melonhub.rbac.dao.ContextDao;
import com.melonhub.rbac.dao.GovernanceDao;
import com.melonhub.rbac.dao.IdentityDao;
import com.melonhub.rbac.dao.RbacDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.identity.IdentityTokenService;
import com.melonhub.rbac.service.ddd.application.DddContextService;
import com.melonhub.rbac.service.ddd.application.DddRbacService;
import com.melonhub.rbac.service.ddd.domain.model.AccessRequestAggregate;
import com.melonhub.rbac.service.ddd.domain.model.AccessReviewAggregate;
import com.melonhub.rbac.service.ddd.domain.model.AdminCredentialAccount;
import com.melonhub.rbac.service.ddd.domain.model.AdminHierarchy;
import com.melonhub.rbac.service.ddd.domain.model.AdminRoleAggregate;
import com.melonhub.rbac.service.ddd.domain.model.AuditHashChainAggregate;
import com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate;
import com.melonhub.rbac.service.ddd.domain.model.PermissionContextAggregate;
import com.melonhub.rbac.service.ddd.domain.model.UserMembershipAggregate;
import com.melonhub.rbac.service.ddd.domain.model.WebhookEndpointAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.DomainEventPublisher;
import com.melonhub.rbac.service.ddd.domain.value.AdminScope;
import com.melonhub.rbac.service.ddd.domain.value.AuditCanonicalizer;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.GrantDuration;
import com.melonhub.rbac.service.ddd.domain.value.IsolationDomain;
import com.melonhub.rbac.service.ddd.domain.value.WebhookSignature;
import com.melonhub.rbac.service.ddd.infrastructure.JpaGovernanceRepositoryAdapter;
import com.melonhub.rbac.service.ddd.infrastructure.JpaIdentityRepositoryAdapter;
import com.melonhub.rbac.service.ddd.infrastructure.JpaPermissionContextRepositoryAdapter;
import com.melonhub.rbac.service.ddd.infrastructure.JpaRbacRepositoryAdapter;
import com.melonhub.rbac.service.ddd.infrastructure.SpringPlatformGatewaysAdapter;
import com.melonhub.rbac.service.port.ContextUseCase;
import com.melonhub.rbac.service.port.RbacUseCase;
import com.melonhub.rbac.service.port.WebhookUseCase;
import com.melonhub.rbac.service.structured.StructuredContextService;
import com.melonhub.rbac.service.structured.StructuredRbacService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
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

/**
 * 纯 DDD 领域聚合根行为测试与结构化/DDD 双策略等价性测试。
 */
class DddDomainAndDualStrategyParityUnitTest {

    @Test
    @DisplayName("DDD-01 审计链哈希规范化（v1/v2）与既有 AuditDao.chainHash 逐字节一致，且可检测断链、篡改与尾缺")
    void auditHashChainCanonicalizerMatchesLegacyAndDetectsAllAnomalies() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 42L);
        row.put("actor", "admin");
        row.put("action", "ROLE_UPDATED");
        row.put("target", "ops|role");
        row.put("department_id", 3L);
        row.put("application_id", null);
        row.put("detail", "detail:value");
        row.put("source_ip", "127.0.0.1");
        row.put("ts", 1732440000L);

        String v1Legacy = AuditDao.chainHash(AuditDao.GENESIS, 1L, row, 5L);
        String v1Ddd = AuditCanonicalizer.chainHash(AuditCanonicalizer.GENESIS, 1L, 5L,
                42L, "admin", "ROLE_UPDATED", "ops|role", 3L, null, "detail:value", "127.0.0.1", 1732440000L);
        assertThat(v1Ddd).isEqualTo(v1Legacy);

        String v2Legacy = AuditDao.chainHash(v1Legacy, 5L, row, 5L);
        String v2Ddd = AuditCanonicalizer.chainHash(v1Ddd, 5L, 5L,
                42L, "admin", "ROLE_UPDATED", "ops|role", 3L, null, "detail:value", "127.0.0.1", 1732440000L);
        assertThat(v2Ddd).isEqualTo(v2Legacy);

        var seal = new AuditHashChainAggregate.SealMetadata(42L, 1L, v1Ddd, 0L, AuditCanonicalizer.GENESIS, 5L);
        var aggregate = new AuditHashChainAggregate(seal);
        var goodEvent = new AuditHashChainAggregate.AuditEventData(
                42L, "admin", "ROLE_UPDATED", "ops|role", 3L, null, "detail:value", "127.0.0.1", 1732440000L,
                1L, AuditCanonicalizer.GENESIS, v1Ddd);
        assertThat(aggregate.verifySegment(List.of(goodEvent), null, 100, Map.of()))
                .containsEntry("ok", true).containsEntry("problem", null).containsEntry("complete", true);

        var modified = new AuditHashChainAggregate.AuditEventData(
                42L, "attacker", "ROLE_UPDATED", "ops|role", 3L, null, "detail:value", "127.0.0.1", 1732440000L,
                1L, AuditCanonicalizer.GENESIS, v1Ddd);
        assertThat(aggregate.verifySegment(List.of(modified), null, 100, Map.of()))
                .containsEntry("ok", false).containsEntry("problem", "MODIFIED");

        var brokenLink = new AuditHashChainAggregate.AuditEventData(
                42L, "admin", "ROLE_UPDATED", "ops|role", 3L, null, "detail:value", "127.0.0.1", 1732440000L,
                1L, "f".repeat(64), v1Ddd);
        assertThat(aggregate.verifySegment(List.of(brokenLink), null, 100, Map.of()))
                .containsEntry("ok", false).containsEntry("problem", "LINK_BROKEN");

        assertThat(aggregate.verifySegment(List.of(), null, 100, Map.of()))
                .containsEntry("ok", false).containsEntry("problem", "TAIL_MISSING");
    }

    @Test
    @DisplayName("DDD-02 管理员角色、凭据登录状态机、审批、复核与 Webhook 聚合根守护全部领域不变量")
    void domainAggregatesEnforceCoreInvariants() {
        // AdminRoleAggregate: built-in role immutable (400), platformAdminOnly forbidden even for SUPER_ADMIN, diff audit
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

        // AdminCredentialAccount: login state machine
        var account = new AdminCredentialAccount(1L, "ops", "Ops", "ADMIN", true, "hash", false,
                "SECRET", 100L, 1L, "MANAGER", true, AdminScope.unrestricted());
        assertThat(account.evaluateLogin(false, null, null)).isEqualTo(AdminCredentialAccount.LoginCheck.PASSWORD_MISMATCH);
        assertThat(account.evaluateLogin(true, "", null)).isEqualTo(AdminCredentialAccount.LoginCheck.MFA_CODE_MISSING);
        assertThat(account.evaluateLogin(true, "123456", 100L)).isEqualTo(AdminCredentialAccount.LoginCheck.MFA_INVALID);
        assertThat(account.evaluateLogin(true, "123456", 101L)).isEqualTo(AdminCredentialAccount.LoginCheck.READY_CONSUME_MFA);

        // AccessRequestAggregate: permanent vs durationDays exclusivity & disabled role check
        var req = new AccessRequestAggregate(9L, "PENDING", 2L, 3L, 4L, 5L, true, "u1", "dev", 7);
        Instant now = Instant.parse("2026-11-24T10:00:00Z");
        assertThatThrownBy(() -> req.planApproval(true, 10, "ok", now))
                .isInstanceOf(DomainException.class).extracting("kind").isEqualTo(DomainException.Kind.BAD_REQUEST);
        var plan = req.planApproval(true, null, "ok", now);
        assertThat(plan.expiresAt()).isNull();
        assertThat(plan.auditDetail()).contains("永久授权");

        // AccessReviewAggregate: summary & deterministic sorted user lock order
        var lockOrder = AccessReviewAggregate.orderedUsersToLock(List.of(
                new AccessReviewAggregate.RevocationCandidate(1L, 9L, 1L, 1L, "g1"),
                new AccessReviewAggregate.RevocationCandidate(2L, 3L, 2L, 1L, "g2")));
        assertThat(lockOrder).containsExactly(3L, 9L);
        assertThat(AccessReviewAggregate.closeSummary(2, 1)).contains("回收 2 条授权", "1 条在复核期间已变更或移除");

        // WebhookEndpointAggregate: disabled test rejected & exponential backoff
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
    @DisplayName("DDD-03 三层角色有效期优先级（逐角色显式 > 批量天数 > 保留原期限）与并发晚到成员识别")
    void domainAuthorizationAggregateHandlesExpiriesAndLateMembers() {
        var domain = new DomainAuthorizationAggregate(new IsolationDomain(1L, 2L));
        Instant now = Instant.parse("2026-11-24T10:00:00Z");
        Instant keptExpiry = now.plus(15, ChronoUnit.DAYS);
        Instant overrideExpiry = now.plus(30, ChronoUnit.DAYS);
        var plan = domain.planUserRoleReplacement(
                List.of(10L, 20L, 30L),
                Map.of(10L, keptExpiry, 20L, keptExpiry, 40L, keptExpiry),
                null,
                List.of(new GrantDuration.RoleExpiryOverride(20L, overrideExpiry)),
                now);
        assertThat(plan.removedRoleIds()).containsExactly(40L);
        assertThat(plan.expiryChanges()).isEqualTo(1);
        assertThat(plan.grantsToApply()).extracting(DomainAuthorizationAggregate.GrantChange::roleId)
                .containsExactly(20L, 30L);

        var latePlan = domain.planRoleMemberReplacement(Set.of(1L, 2L), Set.of(1L, 2L), Set.of(1L, 99L));
        assertThat(latePlan.requiresRetry()).isTrue();
        assertThat(latePlan.lateMembers()).containsExactly(99L);
    }

    @Test
    @DisplayName("DDD-04 管理员层级聚合根拒绝自改角色、删除最后超管与软件管理员错误作用域轴")
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
    @ValueSource(strings = { "structured", "ddd" })
    @DisplayName("PARITY-01 移出用户部门时同步清除遗留域角色、撤销对应域上下文并发出事件与审计（双策略等价）")
    void removingUserDepartmentCleansStaleRoleGrantsIdentically(String strategy) {
        var rbacDao = mock(RbacDao.class);
        var contextDao = mock(ContextDao.class);
        var cache = mock(PermissionCache.class);
        var governanceDao = mock(GovernanceDao.class);
        var identityDao = mock(IdentityDao.class);
        var webhookUseCase = mock(WebhookUseCase.class);
        var eventPublisher = mock(DomainEventPublisher.class);

        when(rbacDao.departmentExists(1L)).thenReturn(true);
        when(rbacDao.lockUser(10L)).thenReturn(Optional.of("u001"));
        when(rbacDao.user(10L)).thenReturn(Optional.of(Map.of("id", 10L, "name", "张三", "externalId", "u001", "email", "")));
        when(rbacDao.lockedUserDepartments(10L)).thenReturn(List.of(1L, 2L));
        when(rbacDao.userRoleAssignments(10L, List.of(2L))).thenReturn(List.of(
                Map.of("department_id", 2L, "application_id", 5L, "role_id", 88L, "role_code", "editor")
        ));

        RbacUseCase service;
        if ("ddd".equals(strategy)) {
            var gateway = new SpringPlatformGatewaysAdapter(cache, mock(PlatformTransactionManager.class),
                    new BCryptPasswordEncoder(4),
                    new IdentityTokenService("unit-test-secret-unit-test-secret-32", "https://identity.test", 7200),
                    new ObjectMapper());
            service = new DddRbacService(
                    new JpaRbacRepositoryAdapter(rbacDao),
                    new JpaPermissionContextRepositoryAdapter(contextDao),
                    new JpaGovernanceRepositoryAdapter(governanceDao),
                    new JpaIdentityRepositoryAdapter(identityDao),
                    gateway, eventPublisher, gateway, gateway);
        } else {
            service = new StructuredRbacService(rbacDao, contextDao, cache, webhookUseCase,
                    governanceDao, identityDao, mock(PlatformTransactionManager.class));
        }

        long id = service.user(new Requests.User("张三", "u001", "", List.of(1L)), 10L, "admin", null);
        assertThat(id).isEqualTo(10L);
        verify(rbacDao).removeUserRolesInDepartments(10L, List.of(2L));
        verify(rbacDao).removeMembership(10L, 2L);
        verify(contextDao).revokeDomain(10L, 2L, 5L);
        verify(cache).touchUser(10L);
        var expectedPayload = Map.<String, Object>of("userId", 10L, "externalId", "u001", "departmentId", 2L, "removed", 1);
        if ("ddd".equals(strategy)) {
            verify(eventPublisher).emit(eq(5L), eq("user.roles.changed"), eq(expectedPayload));
        } else {
            verify(webhookUseCase).emit(eq(5L), eq("user.roles.changed"), eq(expectedPayload));
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = { "structured", "ddd" })
    @DisplayName("PARITY-03 公开健康检查字段（status/database/cache/model）与 scoped 隔离域 403 契约双策略一致")
    void healthContractAndScopedForbiddenMatchIdentically(String strategy) {
        var rbacDao = mock(RbacDao.class);
        var cache = mock(PermissionCache.class);
        when(rbacDao.healthy()).thenReturn(true, false);
        when(cache.health()).thenReturn("UP");
        when(rbacDao.scopedExists("roles", 99L, 1L, 2L)).thenReturn(false);

        RbacUseCase service;
        if ("ddd".equals(strategy)) {
            var gateway = new SpringPlatformGatewaysAdapter(cache, mock(PlatformTransactionManager.class),
                    new BCryptPasswordEncoder(4),
                    new IdentityTokenService("unit-test-secret-unit-test-secret-32", "https://identity.test", 7200),
                    new ObjectMapper());
            service = new DddRbacService(
                    new JpaRbacRepositoryAdapter(rbacDao),
                    new JpaPermissionContextRepositoryAdapter(mock(ContextDao.class)),
                    new JpaGovernanceRepositoryAdapter(mock(GovernanceDao.class)),
                    new JpaIdentityRepositoryAdapter(mock(IdentityDao.class)),
                    gateway, mock(DomainEventPublisher.class), gateway, gateway);
        } else {
            service = new StructuredRbacService(rbacDao, mock(ContextDao.class), cache, mock(WebhookUseCase.class),
                    mock(GovernanceDao.class), mock(IdentityDao.class), mock(PlatformTransactionManager.class));
        }

        assertThat(service.health())
                .containsEntry("status", "UP")
                .containsEntry("database", "UP")
                .containsEntry("cache", "UP")
                .containsEntry("model", "RBAC1");
        assertThat(service.health())
                .containsEntry("status", "DOWN")
                .containsEntry("database", "DOWN")
                .containsEntry("cache", "UP")
                .containsEntry("model", "RBAC1");
        assertThatThrownBy(() -> service.scoped("roles", 99L, 1L, 2L))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PARITY-04 DddRbacService 四个短参数写重载均直接声明 @Transactional，防止代理自调用丢事务")
    void dddRbacShortWriteOverloadsDeclareTransactional() throws Exception {
        var transactional = org.springframework.transaction.annotation.Transactional.class;
        assertThat(DddRbacService.class.getDeclaredMethod("user", Requests.User.class, Long.class, String.class)
                .isAnnotationPresent(transactional)).isTrue();
        assertThat(DddRbacService.class.getDeclaredMethod("userStatus", long.class, boolean.class, String.class)
                .isAnnotationPresent(transactional)).isTrue();
        assertThat(DddRbacService.class.getDeclaredMethod("userRoles", long.class, long.class, long.class, List.class, String.class)
                .isAnnotationPresent(transactional)).isTrue();
        assertThat(DddRbacService.class.getDeclaredMethod("userRoles", long.class, long.class, long.class, List.class, Integer.class, String.class)
                .isAnnotationPresent(transactional)).isTrue();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = { "structured", "ddd" })
    @DisplayName("PARITY-02 switchDepartment=false 时跨部门签发上下文均返回 409 DEPARTMENT_MISMATCH（双策略等价）")
    void contextIssueRejectsImplicitDepartmentSwitchIdentically(String strategy) {
        var contextDao = mock(ContextDao.class);
        var rbacUseCase = mock(RbacUseCase.class);
        var cache = mock(PermissionCache.class);
        when(contextDao.lockUserByExternalId("u001")).thenReturn(List.of(
                Map.of("id", 10L, "enabled", true, "active_department_id", 1L, "context_version", 3L)));
        when(contextDao.applicationEnabled(5L)).thenReturn(true);
        when(contextDao.member(10L, 2L)).thenReturn(true);
        when(contextDao.assignedRoles(10L, 2L, 5L)).thenReturn(List.of(100L));

        ContextUseCase service;
        if ("ddd".equals(strategy)) {
            var gateway = new SpringPlatformGatewaysAdapter(cache, mock(PlatformTransactionManager.class),
                    new BCryptPasswordEncoder(4),
                    new IdentityTokenService("unit-test-secret-unit-test-secret-32", "https://identity.test", 7200),
                    new ObjectMapper());
            service = new DddContextService(new JpaPermissionContextRepositoryAdapter(contextDao),
                    rbacUseCase, gateway, gateway, gateway, gateway);
        } else {
            service = new StructuredContextService(contextDao, rbacUseCase, cache,
                    new ObjectMapper(), mock(PlatformTransactionManager.class));
        }

        assertThatThrownBy(() -> service.issue(5L, new Requests.Context("u001", 2L, null, false), "app"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("DEPARTMENT_MISMATCH");
                });
    }
}
