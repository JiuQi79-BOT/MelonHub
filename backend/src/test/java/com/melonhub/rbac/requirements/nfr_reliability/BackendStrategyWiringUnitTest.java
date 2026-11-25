package com.melonhub.rbac.requirements.nfr_reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.cache.PermissionCache;
import com.melonhub.rbac.dao.AdminRoleDao;
import com.melonhub.rbac.dao.AuditDao;
import com.melonhub.rbac.dao.AuthDao;
import com.melonhub.rbac.dao.ContextDao;
import com.melonhub.rbac.dao.GovernanceDao;
import com.melonhub.rbac.dao.IdentityDao;
import com.melonhub.rbac.dao.RbacDao;
import com.melonhub.rbac.dao.WebhookDao;
import com.melonhub.rbac.identity.IdentityTokenService;
import com.melonhub.rbac.service.ddd.application.DddAdminRoleService;
import com.melonhub.rbac.service.ddd.application.DddAuditSealService;
import com.melonhub.rbac.service.ddd.application.DddAuthService;
import com.melonhub.rbac.service.ddd.application.DddConsoleService;
import com.melonhub.rbac.service.ddd.application.DddContextService;
import com.melonhub.rbac.service.ddd.application.DddGovernanceService;
import com.melonhub.rbac.service.ddd.application.DddIdentityService;
import com.melonhub.rbac.service.ddd.application.DddMaintenanceService;
import com.melonhub.rbac.service.ddd.application.DddRbacService;
import com.melonhub.rbac.service.ddd.application.DddWebhookService;
import com.melonhub.rbac.service.ddd.infrastructure.JpaRbacRepositoryAdapter;
import com.melonhub.rbac.service.port.AdminRoleUseCase;
import com.melonhub.rbac.service.port.AuditSealUseCase;
import com.melonhub.rbac.service.port.AuthUseCase;
import com.melonhub.rbac.service.port.ConsoleUseCase;
import com.melonhub.rbac.service.port.ContextUseCase;
import com.melonhub.rbac.service.port.GovernanceUseCase;
import com.melonhub.rbac.service.port.IdentityUseCase;
import com.melonhub.rbac.service.port.MaintenanceUseCase;
import com.melonhub.rbac.service.port.RbacUseCase;
import com.melonhub.rbac.service.port.WebhookUseCase;
import com.melonhub.rbac.service.structured.StructuredAdminRoleService;
import com.melonhub.rbac.service.structured.StructuredAuditSealService;
import com.melonhub.rbac.service.structured.StructuredAuthService;
import com.melonhub.rbac.service.structured.StructuredConsoleService;
import com.melonhub.rbac.service.structured.StructuredContextService;
import com.melonhub.rbac.service.structured.StructuredGovernanceService;
import com.melonhub.rbac.service.structured.StructuredIdentityService;
import com.melonhub.rbac.service.structured.StructuredMaintenanceService;
import com.melonhub.rbac.service.structured.StructuredRbacService;
import com.melonhub.rbac.service.structured.StructuredWebhookService;
import jakarta.validation.Validator;
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
 * Spring 条件装配双策略切换测试：在纯内存环境下验证 melonhub.backend-strategy=structured 与 ddd
 * 均能零冲突装配全部 10 个用例端口，且另一套实现完全不加载。
 */
class BackendStrategyWiringUnitTest {

    @Configuration
    @ComponentScan(basePackages = {
            "com.melonhub.rbac.service.structured",
            "com.melonhub.rbac.service.ddd.application",
            "com.melonhub.rbac.service.ddd.infrastructure"
    })
    static class StubInfrastructureConfig {
        @Bean RbacDao rbacDao() { return mock(RbacDao.class); }
        @Bean ContextDao contextDao() { return mock(ContextDao.class); }
        @Bean AuthDao authDao() { return mock(AuthDao.class); }
        @Bean AdminRoleDao adminRoleDao() { return mock(AdminRoleDao.class); }
        @Bean GovernanceDao governanceDao() { return mock(GovernanceDao.class); }
        @Bean WebhookDao webhookDao() { return mock(WebhookDao.class); }
        @Bean AuditDao auditDao() { return mock(AuditDao.class); }
        @Bean IdentityDao identityDao() { return mock(IdentityDao.class); }
        @Bean PermissionCache permissionCache() { return mock(PermissionCache.class); }
        @Bean PlatformTransactionManager transactionManager() { return mock(PlatformTransactionManager.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean Validator validator() { return mock(Validator.class); }
        @Bean PasswordEncoder identityPasswordEncoder() { return new BCryptPasswordEncoder(4); }
        @Bean IdentityTokenService identityTokenService() {
            return new IdentityTokenService("unit-test-secret-unit-test-secret-32", "https://identity.test", 7200);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubInfrastructureConfig.class)
            .withPropertyValues(
                    "melonhub.admin-username=admin",
                    "melonhub.admin-password=ValidBootstrapPass!123",
                    "melonhub.backend-strategy=structured",
                    "melonhub.webhooks.delivery=false"
            );

    @Test
    @DisplayName("ARCH-05 默认或 structured 策略仅装配全部 10 个 Structured*Service，且零 DDD Bean")
    void structuredStrategyWiresAllTenUseCasesExclusively() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(RbacUseCase.class).getBean(RbacUseCase.class).isInstanceOf(StructuredRbacService.class);
            assertThat(ctx).hasSingleBean(ContextUseCase.class).getBean(ContextUseCase.class).isInstanceOf(StructuredContextService.class);
            assertThat(ctx).hasSingleBean(AuthUseCase.class).getBean(AuthUseCase.class).isInstanceOf(StructuredAuthService.class);
            assertThat(ctx).hasSingleBean(AdminRoleUseCase.class).getBean(AdminRoleUseCase.class).isInstanceOf(StructuredAdminRoleService.class);
            assertThat(ctx).hasSingleBean(GovernanceUseCase.class).getBean(GovernanceUseCase.class).isInstanceOf(StructuredGovernanceService.class);
            assertThat(ctx).hasSingleBean(WebhookUseCase.class).getBean(WebhookUseCase.class).isInstanceOf(StructuredWebhookService.class);
            assertThat(ctx).hasSingleBean(AuditSealUseCase.class).getBean(AuditSealUseCase.class).isInstanceOf(StructuredAuditSealService.class);
            assertThat(ctx).hasSingleBean(ConsoleUseCase.class).getBean(ConsoleUseCase.class).isInstanceOf(StructuredConsoleService.class);
            assertThat(ctx).hasSingleBean(MaintenanceUseCase.class).getBean(MaintenanceUseCase.class).isInstanceOf(StructuredMaintenanceService.class);
            assertThat(ctx).hasSingleBean(IdentityUseCase.class).getBean(IdentityUseCase.class).isInstanceOf(StructuredIdentityService.class);
            assertThat(ctx).doesNotHaveBean(DddRbacService.class);
            assertThat(ctx).doesNotHaveBean(JpaRbacRepositoryAdapter.class);
        });
    }

    @Test
    @DisplayName("ARCH-06 ddd 策略仅装配全部 10 个 Ddd*Service 与 DDD 适配器，且零 Structured Bean")
    void dddStrategyWiresAllTenUseCasesExclusively() {
        runner.withPropertyValues("melonhub.backend-strategy=ddd").run(ctx -> {
            assertThat(ctx).hasSingleBean(RbacUseCase.class).getBean(RbacUseCase.class).isInstanceOf(DddRbacService.class);
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
            assertThat(ctx).doesNotHaveBean(StructuredRbacService.class);
        });
    }

    @Test
    @DisplayName("ARCH-07 关闭 maintenance.enabled 时，structured 与 ddd 策略均不注册定时维护 Bean")
    void maintenanceDisabledSuppressesBothMaintenanceServices() {
        runner.withPropertyValues("melonhub.backend-strategy=structured", "melonhub.maintenance.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(MaintenanceUseCase.class));
        runner.withPropertyValues("melonhub.backend-strategy=ddd", "melonhub.maintenance.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(MaintenanceUseCase.class));
    }
}
