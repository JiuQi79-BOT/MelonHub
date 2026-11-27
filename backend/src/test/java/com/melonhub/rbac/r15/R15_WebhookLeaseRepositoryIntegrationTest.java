package com.melonhub.rbac.r15;

import static org.assertj.core.api.Assertions.assertThat;

import com.melonhub.rbac.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.service.port.application.ApplicationModels.SaveApplicationCommand;
import com.melonhub.rbac.service.port.application.ApplicationUseCase;
import com.melonhub.rbac.service.port.webhook.WebhookModels.SaveWebhookCommand;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import com.melonhub.rbac.shared.CloudTestDatabaseGuard;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

/** Webhook 投递队列表底层规则测试：保留期清理、过期租约回收与条件认领。对应需求：R15（见 doc/requirements.md）。 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@ContextConfiguration(initializers = CloudTestDatabaseGuard.class)
class R15_WebhookLeaseRepositoryIntegrationTest {
    // 统一测试参数区
    private static final String ACTOR = "test";
    private static final String QUEUE_NAME = "Queue";
    private static final String DEAD_HOOK_URL = "http://127.0.0.1:1/hook";
    private static final int RETENTION_DAYS = 30;
    private static final int PURGE_LIMIT = 5000;
    private static final int EXPIRED_AGE_DAYS = 31;
    private static final int RECENT_AGE_DAYS = 1;
    private static final int MAX_ATTEMPTS = 8;
    private static final int HTTP_OK = 200;

    @Autowired JdbcTemplate db;
    @Autowired WebhookDeliveryRepository deliveries;
    @Autowired ApplicationUseCase apps;
    @Autowired WebhookUseCase webhooks;
    long hook;

    @BeforeEach void setUp() {
        String suffix = Credentials.token().substring(0, 10);
        long app = apps.application(new SaveApplicationCommand(QUEUE_NAME, "q-" + suffix, ""), ACTOR).id();
        hook = webhooks.create(app, new SaveWebhookCommand(QUEUE_NAME, DEAD_HOOK_URL, null, List.of("*"), true), ACTOR).id();
    }

    @DisplayName("R15 Webhook 保留期清理仅删终态记录并保留待发与在途投递")
    @Test void retentionPreservesPendingAndInFlightDeliveries() {
        long pending = insert("PENDING", 2, EXPIRED_AGE_DAYS, -1);
        long sending = insert("SENDING", 3, EXPIRED_AGE_DAYS, 5);
        long delivered = insert("DELIVERED", 1, EXPIRED_AGE_DAYS, -1);
        long failed = insert("FAILED", MAX_ATTEMPTS, EXPIRED_AGE_DAYS, -1);
        long recent = insert("DELIVERED", 1, RECENT_AGE_DAYS, -1);

        assertThat(deliveries.purgeFinished(RETENTION_DAYS, PURGE_LIMIT)).isGreaterThanOrEqualTo(2);
        assertThat(db.queryForList("SELECT id FROM webhook_deliveries WHERE webhook_id=? ORDER BY id", Long.class, hook))
                .containsExactly(pending, sending, recent).doesNotContain(delivered, failed);
        // The retained in-flight delivery can still record its outcome after cleanup.
        assertThat(deliveries.markDelivered(sending, 3, HTTP_OK)).isEqualTo(1);
    }

    @ParameterizedTest
    @DisplayName("R15 末次重试租约过期直接标记 FAILED 不再追加尝试")
    @ValueSource(ints = {1, 3, 8})
    void expiredFinalAttemptFailsInsteadOfStartingAnotherAttempt(int maxAttempts) {
        long id = insert("SENDING", maxAttempts, RECENT_AGE_DAYS, -1);
        deliveries.requeueStale(maxAttempts);

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(deliveries.claim(id, maxAttempts)).isZero();
        assertThat(deliveries.markDelivered(id, maxAttempts, HTTP_OK)).isZero();
        assertThat(db.queryForObject("SELECT attempts FROM webhook_deliveries WHERE id=?", Integer.class, id)).isEqualTo(maxAttempts);
        assertThat(db.queryForObject("SELECT last_error FROM webhook_deliveries WHERE id=?", String.class, id)).contains("lease expired");
    }

    @DisplayName("R15 非末次过期租约自动重试且未过期租约受保护")
    @Test void expiredEarlierAttemptRetriesWhileLiveLeaseIsPreserved() {
        long expired = insert("SENDING", 3, RECENT_AGE_DAYS, -1);
        long live = insert("SENDING", 3, RECENT_AGE_DAYS, 5);
        assertThat(deliveries.requeueStale(MAX_ATTEMPTS)).isGreaterThanOrEqualTo(1);

        assertThat(status(expired)).isEqualTo("PENDING");
        assertThat(deliveries.claim(expired, 3)).isEqualTo(1);
        assertThat(deliveries.markDelivered(expired, 3, HTTP_OK)).isZero();
        assertThat(deliveries.markDelivered(expired, 4, HTTP_OK)).isEqualTo(1);
        assertThat(status(live)).isEqualTo("SENDING");
    }

    private long insert(String status, int attempts, int ageDays, int leaseMinutes) {
        db.update("INSERT INTO webhook_deliveries(webhook_id,event_id,event_type,payload,status,attempts,created_at,next_attempt_at) "
                + "VALUES (?,?,'webhook.test','{}',?,?,TIMESTAMPADD(DAY,-?,CURRENT_TIMESTAMP),TIMESTAMPADD(MINUTE,?,CURRENT_TIMESTAMP))",
                hook, UUID.randomUUID().toString(), status, attempts, ageDays, leaseMinutes);
        return db.queryForObject("SELECT MAX(id) FROM webhook_deliveries WHERE webhook_id=?", Long.class, hook);
    }

    private String status(long id) {
        return db.queryForObject("SELECT status FROM webhook_deliveries WHERE id=?", String.class, id);
    }
}
