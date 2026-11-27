package com.melonhub.rbac.r15;

import static org.assertj.core.api.Assertions.assertThat;

import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.service.port.application.ApplicationModels.SaveApplicationCommand;
import com.melonhub.rbac.service.port.application.ApplicationUseCase;
import com.melonhub.rbac.service.port.webhook.WebhookModels.SaveWebhookCommand;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import com.melonhub.rbac.shared.CloudTestDatabaseGuard;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * 真实多连接并发测试（不加 {@code @Transactional}），验证 Webhook 调度线程池并行推送且不阻塞调度线程。
 * 对应需求：R15（见 doc/requirements.md）。
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = CloudTestDatabaseGuard.class)
class R15_WebhookConcurrencyIntegrationTest {
    // 统一测试参数区
    private static final String ACTOR = "race-test";
    private static final String APP_NAME = "Race App";
    private static final String HOOK_NAME = "慢接收方";
    private static final String SLOW_PATH = "/slow";
    private static final int CONCURRENT_DELIVERIES = 3;
    private static final long RECEIVER_DELAY_MS = 1500L;
    private static final long MAX_SCHEDULER_DISPATCH_MS = 1000L;
    private static final long RECEIVE_WAIT_SECONDS = 10L;
    private static final long DB_POLL_TIMEOUT_MS = 5000L;
    private static final long DB_POLL_INTERVAL_MS = 100L;

    @Autowired ApplicationUseCase apps;
    @Autowired WebhookUseCase webhooks;
    @Autowired JdbcTemplate db;

    @DisplayName("R15 慢 Webhook 接收端既不阻塞调度线程也不互相阻塞")
    @Test void slowWebhookReceiversNeitherHoldTheSchedulerNorEachOther() throws Exception {
        var received = new CountDownLatch(CONCURRENT_DELIVERIES);
        var receivers = Executors.newFixedThreadPool(CONCURRENT_DELIVERIES);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(receivers);
        server.createContext(SLOW_PATH, exchange -> {
            try { Thread.sleep(RECEIVER_DELAY_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        long app = -1;
        try {
            String suffix = Credentials.token().substring(0, 12);
            app = apps.application(new SaveApplicationCommand(APP_NAME, "race-" + suffix, ""), ACTOR).id();
            long hook = webhooks.create(app, new SaveWebhookCommand(
                    HOOK_NAME, "http://127.0.0.1:" + server.getAddress().getPort() + SLOW_PATH,
                    null, List.of("*"), true), ACTOR).id();
            for (int i = 0; i < CONCURRENT_DELIVERIES; i++) webhooks.test(hook, ACTOR);
            long started = System.nanoTime();
            webhooks.scheduledDeliveries();
            long dispatchMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(dispatchMillis).as("the scheduler thread only claims and hands over").isLessThan(MAX_SCHEDULER_DISPATCH_MS);
            assertThat(received.await(RECEIVE_WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            long allMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(allMillis).as("deliveries run side by side, not one after another").isLessThan(CONCURRENT_DELIVERIES * RECEIVER_DELAY_MS);
            long deadline = System.currentTimeMillis() + DB_POLL_TIMEOUT_MS;
            long delivered = 0;
            while (System.currentTimeMillis() < deadline) {
                delivered = db.queryForObject("SELECT COUNT(*) FROM webhook_deliveries WHERE webhook_id=? AND status='DELIVERED'", Long.class, hook);
                if (delivered == CONCURRENT_DELIVERIES) break;
                Thread.sleep(DB_POLL_INTERVAL_MS);
            }
            assertThat(delivered).isEqualTo(CONCURRENT_DELIVERIES);
        } finally {
            server.stop(0);
            receivers.shutdownNow();
            if (app > 0) db.update("DELETE FROM applications WHERE id=?", app);
            db.update("DELETE FROM audit_events WHERE actor=? OR application_id=?", ACTOR, app);
        }
    }
}
