package com.melonhub.rbac.r15;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.gateway.SpringPlatformGatewaysAdapter;
import com.melonhub.rbac.infrastructure.identity.IdentityTokenService;
import com.melonhub.rbac.infrastructure.persistence.gateway.JpaWebhookRepositoryAdapter;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.WebhookRepository;
import com.melonhub.rbac.service.ddd.application.webhook.DddWebhookService;
import com.melonhub.rbac.service.ddd.domain.repository.RbacRepositoryPort;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import com.melonhub.rbac.service.structured.webhook.StructuredWebhookService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 对应需求：R15（见 doc/requirements.md）。
 * 双策略校验 Webhook 请求超时覆盖含响应体读取在内的完整周期。
 */
class R15_WebhookTimeoutUnitTest {

    // =========================================================================
    // 统一测试参数区（超时毫秒、最大重试、样本投递记录、本地回环服务参数）
    // =========================================================================
    private static final String STRATEGY_DDD = "ddd";
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final long DELIVERY_ID = 1L;
    private static final String EVENT_ID = "test";
    private static final String EVENT_TYPE = "user.disabled";
    private static final String EVENT_PAYLOAD = "{}";
    private static final String WEBHOOK_SECRET = "test-secret";
    private static final int TIMEOUT_MS = 500;
    private static final int MAX_ATTEMPTS = 8;
    private static final int CONCURRENCY = 1;
    private static final int MAX_TOTAL_SECONDS = 3;
    private static final String EXPECTED_TIMEOUT_ERROR = "TimeoutException";

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"structured", "ddd"})
    @DisplayName("R15 Webhook 请求超时覆盖含响应体读取的完整周期")
    void responseHeadersDoNotEndTheDeliveryDeadline(String strategy) throws Exception {
        CountDownLatch headers = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(LOOPBACK_HOST, 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 10);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            headers.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        var webhooks = mock(WebhookRepository.class);
        var deliveries = mock(WebhookDeliveryRepository.class);
        var applications = mock(ApplicationRepository.class);
        var auditEvents = mock(AuditEventRepository.class);
        var due = mock(WebhookDeliveryRepository.DueDelivery.class);
        when(due.getId()).thenReturn(DELIVERY_ID);
        when(due.getEventId()).thenReturn(EVENT_ID);
        when(due.getEventType()).thenReturn(EVENT_TYPE);
        when(due.getPayload()).thenReturn(EVENT_PAYLOAD);
        when(due.getAttempts()).thenReturn(0);
        when(due.getUrl()).thenReturn("http://" + LOOPBACK_HOST + ":" + server.getAddress().getPort() + "/");
        when(due.getSecret()).thenReturn(WEBHOOK_SECRET);
        when(deliveries.findDueDeliveries(any(Limit.class))).thenReturn(List.of(due));
        when(deliveries.claim(DELIVERY_ID, 0)).thenReturn(1);
        WebhookUseCase service;
        if (STRATEGY_DDD.equals(strategy)) {
            var gateway = new SpringPlatformGatewaysAdapter(
                    mock(PermissionCache.class), mock(PlatformTransactionManager.class),
                    new BCryptPasswordEncoder(4),
                    new IdentityTokenService("unit-test-secret-unit-test-secret-32", "https://identity.test", 7200),
                    new ObjectMapper());
            service = new DddWebhookService(new JpaWebhookRepositoryAdapter(webhooks, deliveries, applications),
                    mock(RbacRepositoryPort.class),
                    gateway, gateway, gateway, true, TIMEOUT_MS, MAX_ATTEMPTS, CONCURRENCY);
        } else {
            service = new StructuredWebhookService(webhooks, deliveries, applications, auditEvents,
                    new ObjectMapper(), true, TIMEOUT_MS, MAX_ATTEMPTS, CONCURRENCY);
        }
        try {
            long start = System.nanoTime();
            service.deliverNow();
            assertThat(headers.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(MAX_TOTAL_SECONDS));
            verify(deliveries).retry(DELIVERY_ID, 1, 1, null, EXPECTED_TIMEOUT_ERROR);
            verify(deliveries, never()).markDelivered(anyLong(), anyInt(), anyInt());
        } finally {
            release.countDown();
            server.stop(0);
            service.stopSenders();
        }
    }
}
