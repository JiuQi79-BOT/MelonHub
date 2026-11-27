package com.melonhub.rbac.r15;

import static org.assertj.core.api.Assertions.assertThat;

import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SavePermissionCommand;
import com.melonhub.rbac.service.port.webhook.WebhookModels.SaveWebhookCommand;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import com.melonhub.rbac.shared.GovernanceIntegrationSupport;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 真实本地 HTTP 接收端 Webhook HMAC-SHA256 签名投递、停用保队列与条件认领测试。对应需求：R15（见 doc/requirements.md）。 */
class R15_WebhookDeliveryIntegrationTest extends GovernanceIntegrationSupport {
    // 统一测试参数区
    private static final String HOOK_PATH = "/hook";
    private static final String DEAD_HOOK_URL = "http://127.0.0.1:1/hook";
    private static final String SIGNING_SECRET = "wh-test-secret-0123456789";
    private static final String EVENT_USER_ROLES_CHANGED = "user.roles.changed";
    private static final List<String> MUTATION_EVENTS = List.of(
            "permission.updated", "permission.deleted", "role.enabled", "role.deleted", "user.contexts.revoked");
    private static final long RECEIVE_WAIT_SECONDS = 5L;
    private static final int MAX_ATTEMPTS = 8;
    private static final int HTTP_OK = 200;

    @DisplayName("R15 Webhook 投递带 HMAC 签名且失败后按退避重排")
    @Test void webhookDeliveryIsSignedAndFailuresAreRescheduled() throws Exception {
        var received = new CountDownLatch(1);
        var signature = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var event = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(HOOK_PATH, exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst("MelonHub-Signature"));
            event.set(exchange.getRequestHeaders().getFirst("MelonHub-Event"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(HTTP_OK, 0);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            webhooks.create(a1, new SaveWebhookCommand(
                    "测试钩子", "http://127.0.0.1:" + server.getAddress().getPort() + HOOK_PATH,
                    SIGNING_SECRET, List.of(EVENT_USER_ROLES_CHANGED), true), ACTOR);
            rbac.userRoles(u1, d1, a1, List.of(r1, r2), ACTOR);
            webhooks.deliverNow();
            assertThat(received.await(RECEIVE_WAIT_SECONDS, TimeUnit.SECONDS)).as("webhook delivered").isTrue();
            assertThat(event.get()).isEqualTo(EVENT_USER_ROLES_CHANGED);
            String[] parts = signature.get().split(",");
            String timestamp = parts[0].substring(2);
            assertThat(parts[1].substring(3)).isEqualTo(WebhookUseCase.hmacSha256(SIGNING_SECRET, timestamp + "." + body.get()));
            assertThat(mapper.readTree(body.get()).path("data").path("externalId").asText()).isEqualTo(external);
            Long deliveries = db.queryForObject("SELECT COUNT(*) FROM webhook_deliveries WHERE status='DELIVERED'", Long.class);
            assertThat(deliveries).isEqualTo(1);
        } finally {
            server.stop(0);
        }
        // A dead endpoint stays PENDING with backoff instead of being lost.
        webhooks.create(a1, new SaveWebhookCommand("不可用钩子", DEAD_HOOK_URL, null, List.of(EVENT_USER_ROLES_CHANGED), true), ACTOR);
        rbac.userRoles(u1, d1, a1, List.of(r1), ACTOR);
        webhooks.deliverNow();
        var row = db.queryForMap("SELECT status,attempts,last_error FROM webhook_deliveries ORDER BY id DESC LIMIT 1");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).isNotBlank();
    }

    @DisplayName("R15 停用 Webhook 保留投递队列并在重启后继续发送")
    @Test void disabledWebhookKeepsItsQueueUntilEnabledAgain() {
        var hook = new SaveWebhookCommand("停用钩子", DEAD_HOOK_URL, null, List.of(EVENT_USER_ROLES_CHANGED), true);
        long id = webhooks.create(a1, hook, ACTOR).id();
        rbac.userRoles(u1, d1, a1, List.of(r1, r2), ACTOR);
        webhooks.update(id, new SaveWebhookCommand(hook.name(), hook.url(), null, hook.events(), false), ACTOR);
        webhooks.deliverNow();
        var queued = db.queryForMap("SELECT status,attempts FROM webhook_deliveries WHERE webhook_id=?", id);
        assertThat(queued.get("status")).isEqualTo("PENDING");
        assertThat(((Number) queued.get("attempts")).intValue()).isZero();
        webhooks.update(id, hook, ACTOR);
        webhooks.deliverNow();
        assertThat(db.queryForObject("SELECT attempts FROM webhook_deliveries WHERE webhook_id=?", Integer.class, id)).isEqualTo(1);
    }

    @DisplayName("R15 租约过期的陈旧 Webhook 读取无法认领或覆写结果")
    @Test void staleWebhookReadsCannotClaimOrFinishADelivery() {
        long hook = webhooks.create(a1, new SaveWebhookCommand("认领", DEAD_HOOK_URL, null, List.of(EVENT_USER_ROLES_CHANGED), true), ACTOR).id();
        rbac.userRoles(u1, d1, a1, List.of(r1, r2), ACTOR);
        long delivery = db.queryForObject("SELECT id FROM webhook_deliveries WHERE webhook_id=?", Long.class, hook);
        assertThat(webhookDeliveries.claim(delivery, 1)).as("attempt count differs from the read").isZero();
        db.update("UPDATE webhook_deliveries SET next_attempt_at=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP) WHERE id=?", delivery);
        assertThat(webhookDeliveries.claim(delivery, 0)).as("not due yet").isZero();
        db.update("UPDATE webhook_deliveries SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", delivery);
        assertThat(webhookDeliveries.claim(delivery, 0)).isEqualTo(1);
        assertThat(webhookDeliveries.claim(delivery, 0)).isZero();
        // The first worker's lease lapses and another worker claims the delivery again.
        db.update("UPDATE webhook_deliveries SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", delivery);
        webhookDeliveries.requeueStale(MAX_ATTEMPTS);
        assertThat(webhookDeliveries.claim(delivery, 1)).isEqualTo(1);
        assertThat(webhookDeliveries.markDelivered(delivery, 1, HTTP_OK)).as("the lapsed claim cannot write its result").isZero();
        assertThat(webhookDeliveries.markDelivered(delivery, 2, HTTP_OK)).isEqualTo(1);
    }

    @DisplayName("R15 Webhook 列表返回数组契约且权限变更触发通知")
    @Test void webhookListReturnsArraysAndPermissionMutationsAreAnnounced() {
        long hook = webhooks.create(a1, new SaveWebhookCommand("变更", DEAD_HOOK_URL, null, List.of("*"), true), ACTOR).id();
        assertThat(webhooks.webhooks(a1).get(0).events()).isEqualTo(List.of("*"));
        webhooks.update(hook, new SaveWebhookCommand("变更", DEAD_HOOK_URL, null, MUTATION_EVENTS, true), ACTOR);
        assertThat(webhooks.webhooks(a1).get(0).events()).hasSize(MUTATION_EVENTS.size());
        long permission = db.queryForObject("SELECT id FROM permissions WHERE department_id=? AND application_id=? AND code=?",
                Long.class, d1, a1, PERM_WRITE_CODE);
        rbac.permission(new SavePermissionCommand("Write", "item:edit", "item", "edit"), permission, d1, a1, ACTOR);
        rbac.deletePermission(permission, d1, a1, ACTOR);
        rbac.roleStatus(r2, d1, a1, true, ACTOR);
        rbac.deleteRole(r2, d1, a1, ACTOR);
        orgs.revokeUserContexts(u1, ACTOR, null);
        assertThat(db.queryForList("SELECT event_type FROM webhook_deliveries WHERE webhook_id=? ORDER BY id", String.class, hook))
                .containsExactlyElementsOf(MUTATION_EVENTS);
    }
}
