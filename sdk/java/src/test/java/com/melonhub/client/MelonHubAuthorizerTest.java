package com.melonhub.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MelonHubAuthorizerTest {

    private StubServer stub;
    private MelonHubAuthorizer authorizer;
    private final AtomicInteger issues = new AtomicInteger();
    private final AtomicInteger checks = new AtomicInteger();

    private static String futureExpiry() {
        return Instant.now().plusSeconds(3600).toString();
    }

    @BeforeEach
    void setUp() throws IOException {
        stub = new StubServer();
        stub.on("POST", "/api/v1/contexts", (ex, body) -> {
            int n = issues.incrementAndGet();
            return StubServer.Response.json(200, "{\"contextToken\":\"tok" + n + "\",\"userId\":1,\"departmentId\":1,\"applicationId\":2,\"contextVersion\":" + n + ",\"expiresAt\":\"" + futureExpiry() + "\"}");
        });
        authorizer = new MelonHubAuthorizer(new MelonHubClient(stub.baseUrl(), "mh_test"));
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private void allowChecks() {
        stub.on("POST", "/api/v1/contexts/check", (ex, body) -> {
            checks.incrementAndGet();
            return StubServer.Response.json(200, "{\"allowed\":true,\"decisions\":{\"item:read\":true},\"contextVersion\":1}");
        });
    }

    @Test
    void cachesTokenAcrossCalls() {
        allowChecks();
        assertThat(authorizer.hasPermission("u1", 1L, "item:read")).isTrue();
        assertThat(authorizer.hasPermission("u1", 1L, "item:read")).isTrue();
        assertThat(issues.get()).isEqualTo(1);
        assertThat(checks.get()).isEqualTo(2);
    }

    @Test
    void rebuildsContextOnceOnContextInvalidWithoutSwitching() {
        List<String> bodies = new CopyOnWriteArrayList<>();
        stub.on("POST", "/api/v1/contexts", (ex, body) -> {
            bodies.add(body);
            int n = issues.incrementAndGet();
            return StubServer.Response.json(200, "{\"contextToken\":\"tok" + n + "\",\"expiresAt\":\"" + futureExpiry() + "\"}");
        });
        AtomicInteger checkCalls = new AtomicInteger();
        stub.on("POST", "/api/v1/contexts/check", (ex, body) -> {
            if (checkCalls.incrementAndGet() == 1) {
                return StubServer.Response.json(401, "{\"code\":\"CONTEXT_INVALID\",\"message\":\"角色已重新分配\"}");
            }
            assertThat(ex.getRequestHeaders().getFirst("X-Context-Token")).isEqualTo("tok2");
            return StubServer.Response.json(200, "{\"allowed\":true,\"decisions\":{},\"contextVersion\":2}");
        });
        assertThat(authorizer.hasPermission("u1", 1L, "item:read")).isTrue();
        assertThat(issues.get()).isEqualTo(2);
        assertThat(bodies).allSatisfy(body -> assertThat(body).contains("\"switchDepartment\":false"));
    }

    @Test
    void departmentSelectionReusesTheApplicationContext() {
        allowChecks();
        assertThat(authorizer.hasPermission("u1", 1L, "item:read")).isTrue();
        assertThat(authorizer.hasPermission("u1", 2L, "item:read")).isTrue();
        assertThat(authorizer.switchDepartment("u1", 2L)).isEqualTo("tok1");
        assertThat(issues.get()).isEqualTo(1);
    }

    @Test
    void switchingDropsCachedContextsOfOtherDepartments() {
        allowChecks();
        authorizer.hasPermission("u1", 1L, "item:read");
        authorizer.hasPermission("u2", 1L, "item:read");
        authorizer.switchDepartment("u1", 2L);
        assertThat(issues.get()).isEqualTo(2);
        authorizer.hasPermission("u1", 1L, "item:read");
        authorizer.hasPermission("u2", 1L, "item:read");
        assertThat(issues.get()).isEqualTo(2); // 部门只管理成员，不结束软件上下文
    }

    @Test
    void failureWhileRebuildingStaysFailClosed() {
        stub.on("POST", "/api/v1/contexts", (ex, body) -> issues.incrementAndGet() == 1
                ? StubServer.Response.json(200, "{\"contextToken\":\"tok1\",\"expiresAt\":\"" + futureExpiry() + "\"}")
                : StubServer.Response.json(500, "{\"code\":\"INTERNAL\",\"message\":\"boom\"}"));
        stub.on("POST", "/api/v1/contexts/check", (ex, body) ->
                StubServer.Response.json(401, "{\"code\":\"CONTEXT_INVALID\",\"message\":\"已失效\"}"));
        assertThat(authorizer.hasPermission("u1", 1L, "item:read")).isFalse();
        assertThat(issues.get()).isEqualTo(2);
    }

    @Test
    void failClosedOnServerError() {
        stub.on("POST", "/api/v1/contexts", (ex, body) -> StubServer.Response.json(500, "{\"code\":\"INTERNAL\",\"message\":\"boom\"}"));
        assertThat(authorizer.hasPermission("u1", 1L, "item:read")).isFalse();
    }

    @Test
    void failClosedOnNetworkError() {
        stub.close();
        assertThat(authorizer.hasPermission("u1", 1L, "item:read")).isFalse();
    }

    @Test
    void webhookEvictsCachedContext() throws IOException {
        allowChecks();
        authorizer.hasPermission("u1", 1L, "item:read");
        authorizer.hasPermission("u2", 1L, "item:read");
        assertThat(issues.get()).isEqualTo(2);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode data = mapper.createObjectNode().put("externalId", "u1").put("departmentId", 1);
        authorizer.handleWebhookEvent("user.disabled", data);

        authorizer.hasPermission("u1", 1L, "item:read");
        authorizer.hasPermission("u2", 1L, "item:read");
        assertThat(issues.get()).isEqualTo(3); // 只有 u1 重建
    }

    @Test
    void domainAndApplicationEventsEvictTheAffectedContexts() {
        authorizer.token("u1", 1L);
        authorizer.token("u2", 1L);
        authorizer.token("u1", 2L);
        var mapper = new ObjectMapper();
        authorizer.handleWebhookEvent("permission.updated", mapper.createObjectNode().put("departmentId", 1));
        authorizer.token("u1", 1L);
        authorizer.token("u2", 1L);
        authorizer.token("u1", 2L);
        assertThat(issues.get()).isEqualTo(4);
        authorizer.handleWebhookEvent("application.disabled", mapper.createObjectNode());
        authorizer.token("u1", 1L);
        authorizer.token("u2", 1L);
        authorizer.token("u1", 2L);
        assertThat(issues.get()).isEqualTo(6);
    }

    @Test
    void evictAndEvictAll() {
        allowChecks();
        authorizer.hasPermission("u1", 1L, "item:read");
        authorizer.evict("u1", 1L);
        authorizer.hasPermission("u1", 1L, "item:read");
        assertThat(issues.get()).isEqualTo(2);

        authorizer.evictAll();
        authorizer.hasPermission("u1", 1L, "item:read");
        assertThat(issues.get()).isEqualTo(3);
    }
}
