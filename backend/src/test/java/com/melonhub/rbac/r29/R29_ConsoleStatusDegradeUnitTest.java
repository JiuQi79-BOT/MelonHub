package com.melonhub.rbac.r29;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.api.console.ConsoleVoMapper;
import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.persistence.gateway.JpaConsoleMetricsAdapter;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessRequestRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AccessReviewRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditSealRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionContextRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.melonhub.rbac.service.ddd.application.console.DddConsoleService;
import com.melonhub.rbac.service.port.console.ConsoleUseCase;
import com.melonhub.rbac.service.port.security.Visibility;
import com.melonhub.rbac.service.structured.console.StructuredConsoleService;
import com.melonhub.rbac.shared.JsonShapeSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

/** 对应需求：R29（见 doc/requirements.md）。覆盖结构化与 DDD 双策略。 */
class R29_ConsoleStatusDegradeUnitTest {

    // =========================================================================
    // 统一测试参数区（降级保留字段名、状态常量与序列化器）
    // =========================================================================
    private static final String STRATEGY_DDD = "ddd";
    private static final String STATUS_DOWN = "DOWN";
    private static final List<String> NULLABLE_DETAIL_KEYS = List.of(
            "counts", "pendingRequests", "openReviews", "webhookBacklog", "expiringIn7Days", "audit");
    private static final ObjectMapper MAPPER = JsonShapeSupport.springCompatibleMapper(JsonShapeSupport.DEFAULT_TIME_ZONE);

    private static ConsoleUseCase build(String strategy, AuditSealRepository auditSeals,
                                        AccessRequestRepository accessRequests, PermissionCache cache) {
        var users = mock(UserRepository.class);
        var departments = mock(DepartmentRepository.class);
        var applications = mock(ApplicationRepository.class);
        var roles = mock(RoleRepository.class);
        var permissions = mock(PermissionRepository.class);
        var userRoles = mock(UserRoleRepository.class);
        var contexts = mock(PermissionContextRepository.class);
        var accessReviews = mock(AccessReviewRepository.class);
        var webhookDeliveries = mock(WebhookDeliveryRepository.class);
        var auditEvents = mock(AuditEventRepository.class);
        if (STRATEGY_DDD.equals(strategy)) {
            return new DddConsoleService(
                    new JpaConsoleMetricsAdapter(users, departments, applications, roles, permissions,
                            userRoles, contexts, accessRequests, accessReviews, webhookDeliveries, auditEvents, auditSeals, cache));
        }
        return new StructuredConsoleService(users, departments, applications, roles, permissions,
                userRoles, contexts, accessRequests, accessReviews, webhookDeliveries, auditEvents, auditSeals, cache);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"structured", "ddd"})
    @DisplayName("R29 数据库不可达时系统状态看板仍返回完整键结构")
    void anUnreachableDatabaseStillAnswersWithEveryKey(String strategy) throws Exception {
        var auditSeals = mock(AuditSealRepository.class);
        var accessRequests = mock(AccessRequestRepository.class);
        var cache = mock(PermissionCache.class);
        when(auditSeals.isDatabaseHealthy()).thenThrow(new DataAccessResourceFailureException("down"));
        var status = build(strategy, auditSeals, accessRequests, cache).status(new Visibility(List.of(), List.of()));
        assertThat(status.database()).isEqualTo(STATUS_DOWN);
        assertThat(status.counts()).isNull();
        var json = MAPPER.readTree(MAPPER.writeValueAsString(ConsoleVoMapper.toSystemStatusVO(status)));
        assertThat(json.get("database").asText()).isEqualTo(STATUS_DOWN);
        NULLABLE_DETAIL_KEYS.forEach(key -> {
            assertThat(json.has(key)).as(key).isTrue();
            assertThat(json.get(key).isNull()).as(key).isTrue();
        });
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"structured", "ddd"})
    @DisplayName("R29 数据库查询半途失败时丢弃半截数据并回报 DOWN")
    void aDatabaseFailingHalfwayDropsWhatItAlreadyReturned(String strategy) throws Exception {
        var auditSeals = mock(AuditSealRepository.class);
        var accessRequests = mock(AccessRequestRepository.class);
        var cache = mock(PermissionCache.class);
        when(auditSeals.isDatabaseHealthy()).thenReturn(true);
        when(accessRequests.countPending(any())).thenThrow(new DataAccessResourceFailureException("lost"));
        var status = build(strategy, auditSeals, accessRequests, cache).status(new Visibility(List.of(), List.of()));
        assertThat(status.database()).isEqualTo(STATUS_DOWN);
        var json = MAPPER.readTree(MAPPER.writeValueAsString(ConsoleVoMapper.toSystemStatusVO(status)));
        assertThat(json.get("database").asText()).isEqualTo(STATUS_DOWN);
        NULLABLE_DETAIL_KEYS.forEach(key -> {
            assertThat(json.has(key)).as(key).isTrue();
            assertThat(json.get(key).isNull()).as(key).isTrue();
        });
    }
}
