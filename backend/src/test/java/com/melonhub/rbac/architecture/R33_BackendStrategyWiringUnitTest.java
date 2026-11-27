package com.melonhub.rbac.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.identity.IdentityTokenService;
import com.melonhub.rbac.infrastructure.persistence.gateway.JpaRbacRepositoryAdapter;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessRequestRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessReviewItemRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessReviewRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminAccountRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminFunctionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminRoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminSessionRepository;
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
import com.melonhub.rbac.infrastructure.persistence.repository.WebhookRepository;
import com.melonhub.rbac.service.ddd.application.admin.DddAdminRoleService;
import com.melonhub.rbac.service.ddd.application.admin.DddAuthService;
import com.melonhub.rbac.service.ddd.application.application.DddApplicationService;
import com.melonhub.rbac.service.ddd.application.audit.DddAuditSealService;
import com.melonhub.rbac.service.ddd.application.audit.DddAuditService;
import com.melonhub.rbac.service.ddd.application.console.DddConsoleService;
import com.melonhub.rbac.service.ddd.application.context.DddContextService;
import com.melonhub.rbac.service.ddd.application.domainrbac.DddDomainRbacService;
import com.melonhub.rbac.service.ddd.application.governance.DddGovernanceService;
import com.melonhub.rbac.service.ddd.application.identity.DddIdentityService;
import com.melonhub.rbac.service.ddd.application.maintenance.DddMaintenanceService;
import com.melonhub.rbac.service.ddd.application.organization.DddOrganizationService;
import com.melonhub.rbac.service.ddd.application.webhook.DddWebhookService;
import com.melonhub.rbac.service.port.admin.AdminRoleUseCase;
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import com.melonhub.rbac.service.port.application.ApplicationUseCase;
import com.melonhub.rbac.service.port.audit.AuditSealUseCase;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.console.ConsoleUseCase;
import com.melonhub.rbac.service.port.context.ContextUseCase;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacUseCase;
import com.melonhub.rbac.service.port.governance.GovernanceUseCase;
import com.melonhub.rbac.service.port.identity.IdentityUseCase;
import com.melonhub.rbac.service.port.maintenance.MaintenanceUseCase;
import com.melonhub.rbac.service.port.organization.OrganizationUseCase;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import com.melonhub.rbac.service.structured.admin.StructuredAdminRoleService;
import com.melonhub.rbac.service.structured.admin.StructuredAuthService;
import com.melonhub.rbac.service.structured.application.StructuredApplicationService;
import com.melonhub.rbac.service.structured.audit.StructuredAuditSealService;
import com.melonhub.rbac.service.structured.audit.StructuredAuditService;
import com.melonhub.rbac.service.structured.console.StructuredConsoleService;
import com.melonhub.rbac.service.structured.context.StructuredContextService;
import com.melonhub.rbac.service.structured.domainrbac.StructuredDomainRbacService;
import com.melonhub.rbac.service.structured.governance.StructuredGovernanceService;
import com.melonhub.rbac.service.structured.identity.StructuredIdentityService;
import com.melonhub.rbac.service.structured.maintenance.StructuredMaintenanceService;
import com.melonhub.rbac.service.structured.organization.StructuredOrganizationService;
import com.melonhub.rbac.service.structured.webhook.StructuredWebhookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring 条件装配双策略切换测试：在纯内存环境下验证 structured 与 ddd 均能零冲突、无循环依赖地
 * 排他装配全部 13 个按业务拆分的用例端口（覆盖 R33）。
 */
class R33_BackendStrategyWiringUnitTest {

    // =========================================================================
    // 统一测试参数区（引导配置属性、策略名与测试密钥）
    // =========================================================================
    private static final String PROP_ADMIN_USER = "melonhub.admin-username=admin";
    private static final String PROP_ADMIN_PASS = "melonhub.admin-password=ValidBootstrapPass!123";
    private static final String PROP_STRATEGY_STRUCTURED = "melonhub.backend-strategy=structured";
    private static final String PROP_STRATEGY_DDD = "melonhub.backend-strategy=ddd";
    private static final String PROP_WEBHOOK_DELIVERY_OFF = "melonhub.webhooks.delivery=false";
    private static final String PROP_MAINTENANCE_OFF = "melonhub.maintenance.enabled=false";
    private static final String IDENTITY_JWT_SECRET = "unit-test-secret-unit-test-secret-32";
    private static final String IDENTITY_ISSUER = "https://identity.test";
    private static final long IDENTITY_TTL_SECONDS = 7200L;

    @Configuration
    @ComponentScan(basePackages = {
            "com.melonhub.rbac.service.structured",
            "com.melonhub.rbac.service.ddd.application",
            "com.melonhub.rbac.infrastructure.persistence.gateway",
            "com.melonhub.rbac.infrastructure.gateway"
    })
    static class StubInfrastructureConfig {
        @Bean AccessRequestRepository accessRequestRepository() { return mock(AccessRequestRepository.class); }
        @Bean AccessReviewItemRepository accessReviewItemRepository() { return mock(AccessReviewItemRepository.class); }
        @Bean AccessReviewRepository accessReviewRepository() { return mock(AccessReviewRepository.class); }
        @Bean AdminAccountRepository adminAccountRepository() { return mock(AdminAccountRepository.class); }
        @Bean AdminFunctionRepository adminFunctionRepository() { return mock(AdminFunctionRepository.class); }
        @Bean AdminRoleRepository adminRoleRepository() { return mock(AdminRoleRepository.class); }
        @Bean AdminSessionRepository adminSessionRepository() { return mock(AdminSessionRepository.class); }
        @Bean ApplicationKeyRepository applicationKeyRepository() { return mock(ApplicationKeyRepository.class); }
        @Bean ApplicationRepository applicationRepository() { return mock(ApplicationRepository.class); }
        @Bean AuditEventRepository auditEventRepository() { return mock(AuditEventRepository.class); }
        @Bean AuditSealRepository auditSealRepository() { return mock(AuditSealRepository.class); }
        @Bean ContextRoleRepository contextRoleRepository() { return mock(ContextRoleRepository.class); }
        @Bean DepartmentRepository departmentRepository() { return mock(DepartmentRepository.class); }
        @Bean IdentityCredentialRepository identityCredentialRepository() { return mock(IdentityCredentialRepository.class); }
        @Bean IdentitySessionRepository identitySessionRepository() { return mock(IdentitySessionRepository.class); }
        @Bean MembershipRepository membershipRepository() { return mock(MembershipRepository.class); }
        @Bean PermissionContextRepository permissionContextRepository() { return mock(PermissionContextRepository.class); }
        @Bean PermissionRepository permissionRepository() { return mock(PermissionRepository.class); }
        @Bean RoleInheritanceRepository roleInheritanceRepository() { return mock(RoleInheritanceRepository.class); }
        @Bean RolePermissionRepository rolePermissionRepository() { return mock(RolePermissionRepository.class); }
        @Bean RoleRepository roleRepository() { return mock(RoleRepository.class); }
        @Bean UserRepository userRepository() { return mock(UserRepository.class); }
        @Bean UserRoleRepository userRoleRepository() { return mock(UserRoleRepository.class); }
        @Bean WebhookDeliveryRepository webhookDeliveryRepository() { return mock(WebhookDeliveryRepository.class); }
        @Bean WebhookRepository webhookRepository() { return mock(WebhookRepository.class); }
        @Bean PermissionCache permissionCache() { return mock(PermissionCache.class); }
        @Bean PlatformTransactionManager transactionManager() { return mock(PlatformTransactionManager.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean PasswordEncoder identityPasswordEncoder() { return new BCryptPasswordEncoder(4); }
        @Bean IdentityTokenService identityTokenService() {
            return new IdentityTokenService(IDENTITY_JWT_SECRET, IDENTITY_ISSUER, IDENTITY_TTL_SECONDS);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubInfrastructureConfig.class)
            .withPropertyValues(PROP_ADMIN_USER, PROP_ADMIN_PASS, PROP_STRATEGY_STRUCTURED, PROP_WEBHOOK_DELIVERY_OFF);

    @Test
    @DisplayName("WIRING-01 默认或 structured 策略排他装配全部 13 个 Structured*Service，且零 DDD Bean")
    void structuredStrategyWiresAllThirteenUseCasesExclusively() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(OrganizationUseCase.class).getBean(OrganizationUseCase.class).isInstanceOf(StructuredOrganizationService.class);
            assertThat(ctx).hasSingleBean(ApplicationUseCase.class).getBean(ApplicationUseCase.class).isInstanceOf(StructuredApplicationService.class);
            assertThat(ctx).hasSingleBean(DomainRbacUseCase.class).getBean(DomainRbacUseCase.class).isInstanceOf(StructuredDomainRbacService.class);
            assertThat(ctx).hasSingleBean(AuditUseCase.class).getBean(AuditUseCase.class).isInstanceOf(StructuredAuditService.class);
            assertThat(ctx).hasSingleBean(ContextUseCase.class).getBean(ContextUseCase.class).isInstanceOf(StructuredContextService.class);
            assertThat(ctx).hasSingleBean(AuthUseCase.class).getBean(AuthUseCase.class).isInstanceOf(StructuredAuthService.class);
            assertThat(ctx).hasSingleBean(AdminRoleUseCase.class).getBean(AdminRoleUseCase.class).isInstanceOf(StructuredAdminRoleService.class);
            assertThat(ctx).hasSingleBean(GovernanceUseCase.class).getBean(GovernanceUseCase.class).isInstanceOf(StructuredGovernanceService.class);
            assertThat(ctx).hasSingleBean(WebhookUseCase.class).getBean(WebhookUseCase.class).isInstanceOf(StructuredWebhookService.class);
            assertThat(ctx).hasSingleBean(AuditSealUseCase.class).getBean(AuditSealUseCase.class).isInstanceOf(StructuredAuditSealService.class);
            assertThat(ctx).hasSingleBean(ConsoleUseCase.class).getBean(ConsoleUseCase.class).isInstanceOf(StructuredConsoleService.class);
            assertThat(ctx).hasSingleBean(MaintenanceUseCase.class).getBean(MaintenanceUseCase.class).isInstanceOf(StructuredMaintenanceService.class);
            assertThat(ctx).hasSingleBean(IdentityUseCase.class).getBean(IdentityUseCase.class).isInstanceOf(StructuredIdentityService.class);
            assertThat(ctx).doesNotHaveBean(DddOrganizationService.class);
            assertThat(ctx).doesNotHaveBean(DddDomainRbacService.class);
            assertThat(ctx).doesNotHaveBean(JpaRbacRepositoryAdapter.class);
        });
    }

    @Test
    @DisplayName("WIRING-02 ddd 策略排他装配全部 13 个 Ddd*Service 与 JPA 网关适配器，且零 Structured Bean")
    void dddStrategyWiresAllThirteenUseCasesExclusively() {
        runner.withPropertyValues(PROP_STRATEGY_DDD).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(OrganizationUseCase.class).getBean(OrganizationUseCase.class).isInstanceOf(DddOrganizationService.class);
            assertThat(ctx).hasSingleBean(ApplicationUseCase.class).getBean(ApplicationUseCase.class).isInstanceOf(DddApplicationService.class);
            assertThat(ctx).hasSingleBean(DomainRbacUseCase.class).getBean(DomainRbacUseCase.class).isInstanceOf(DddDomainRbacService.class);
            assertThat(ctx).hasSingleBean(AuditUseCase.class).getBean(AuditUseCase.class).isInstanceOf(DddAuditService.class);
            assertThat(ctx).hasSingleBean(ContextUseCase.class).getBean(ContextUseCase.class).isInstanceOf(DddContextService.class);
            assertThat(ctx).hasSingleBean(AuthUseCase.class).getBean(AuthUseCase.class).isInstanceOf(DddAuthService.class);
            assertThat(ctx).hasSingleBean(AdminRoleUseCase.class).getBean(AdminRoleUseCase.class).isInstanceOf(DddAdminRoleService.class);
            assertThat(ctx).hasSingleBean(GovernanceUseCase.class).getBean(GovernanceUseCase.class).isInstanceOf(DddGovernanceService.class);
            assertThat(ctx).hasSingleBean(WebhookUseCase.class).getBean(WebhookUseCase.class).isInstanceOf(DddWebhookService.class);
            assertThat(ctx).hasSingleBean(AuditSealUseCase.class).getBean(AuditSealUseCase.class).isInstanceOf(DddAuditSealService.class);
            assertThat(ctx).hasSingleBean(ConsoleUseCase.class).getBean(ConsoleUseCase.class).isInstanceOf(DddConsoleService.class);
            assertThat(ctx).hasSingleBean(MaintenanceUseCase.class).getBean(MaintenanceUseCase.class).isInstanceOf(DddMaintenanceService.class);
            assertThat(ctx).hasSingleBean(IdentityUseCase.class).getBean(IdentityUseCase.class).isInstanceOf(DddIdentityService.class);
            assertThat(ctx).hasSingleBean(JpaRbacRepositoryAdapter.class);
            assertThat(ctx).doesNotHaveBean(StructuredOrganizationService.class);
            assertThat(ctx).doesNotHaveBean(StructuredDomainRbacService.class);
        });
    }

    @Test
    @DisplayName("WIRING-03 关闭 maintenance.enabled 时，双策略均不注册定时维护 Bean")
    void maintenanceDisabledSuppressesBothMaintenanceServices() {
        runner.withPropertyValues(PROP_STRATEGY_STRUCTURED, PROP_MAINTENANCE_OFF)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(MaintenanceUseCase.class));
        runner.withPropertyValues(PROP_STRATEGY_DDD, PROP_MAINTENANCE_OFF)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(MaintenanceUseCase.class));
    }
}
