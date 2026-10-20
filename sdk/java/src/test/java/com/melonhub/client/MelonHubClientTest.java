package com.melonhub.client;

import com.melonhub.client.dto.AccessRequestView;
import com.melonhub.client.dto.CheckResult;
import com.melonhub.client.dto.ContextView;
import com.melonhub.client.dto.UserAccessView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MelonHubClientTest {

    private StubServer stub;
    private MelonHubClient client;

    @BeforeEach
    void setUp() throws IOException {
        stub = new StubServer();
        client = MelonHubClient.builder(stub.baseUrl(), "mh_test")
                .requestTimeout(Duration.ofSeconds(2))
                .maxRetries(2)
                .build();
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void timeoutIncludesAResponseBodyThatNeverFinishes() throws Exception {
        var headers = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 10);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            headers.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var timed = MelonHubClient.builder("http://127.0.0.1:" + server.getAddress().getPort(), "test")
                    .requestTimeout(Duration.ofMillis(500)).maxRetries(0).build();
            long start = System.nanoTime();
            assertThatThrownBy(() -> timed.check("tok", List.of("read"))).isInstanceOf(MelonHubException.class).hasMessageContaining("超时");
            assertThat(headers.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void issueContextParsesViewAndOmitsNullRoles() {
        stub.on("POST", "/api/v1/contexts", (ex, body) -> {
            assertThat(ex.getRequestHeaders().getFirst("X-API-Key")).isEqualTo("mh_test");
            assertThat(body).contains("\"externalId\":\"u1\"").doesNotContain("activeRoleIds", "departmentId", "switchDepartment");
            return StubServer.Response.json(200, """
                    {"contextToken":"tok","userId":1,"applicationId":2,"contextVersion":3,
                     "expiresAt":"2026-10-18T17:00:00Z","roles":[{"id":1,"name":"管理员","code":"admin"}],
                     "permissions":["item:read"]}""");
        });
        ContextView view = client.issueContext("u1", null);
        assertThat(view.getContextToken()).isEqualTo("tok");
        assertThat(view.getContextVersion()).isEqualTo(3);
        assertThat(view.getPermissions()).containsExactly("item:read");
        assertThat(view.getRoles().get(0).getCode()).isEqualTo("admin");
    }

    @Test
    void effectiveSendsContextTokenHeader() {
        stub.on("GET", "/api/v1/contexts/effective", (ex, body) -> {
            assertThat(ex.getRequestHeaders().getFirst("X-Context-Token")).isEqualTo("tok");
            return StubServer.Response.json(200, "{\"userId\":1,\"contextVersion\":3}");
        });
        assertThat(client.effective("tok").getContextVersion()).isEqualTo(3);
    }

    @Test
    void checkParsesDecisions() {
        stub.on("POST", "/api/v1/contexts/check", (ex, body) ->
                StubServer.Response.json(200, "{\"allowed\":true,\"decisions\":{\"a\":true,\"b\":false},\"contextVersion\":3}"));
        CheckResult result = client.check("tok", List.of("a", "b"));
        assertThat(result.allowed()).isTrue();
        assertThat(result.decisions()).containsEntry("a", true).containsEntry("b", false);
        assertThat(result.contextVersion()).isEqualTo(3);
    }

    @Test
    void revokeAndAccessRequestsEndpoints() {
        stub.on("DELETE", "/api/v1/contexts", (ex, body) -> StubServer.Response.json(200, "{\"ok\":true}"));
        client.revoke("tok");
        assertThat(stub.hits("DELETE", "/api/v1/contexts")).isEqualTo(1);

        stub.on("POST", "/api/v1/access-requests", (ex, body) -> {
            assertThat(body).contains("\"roleId\":5").doesNotContain("durationDays", "departmentId");
            return StubServer.Response.json(200, "{\"id\":9,\"status\":\"PENDING\"}");
        });
        AccessRequestView created = client.submitAccessRequest("u1", 5L, "需要", null);
        assertThat(created.getId()).isEqualTo(9);
        assertThat(created.getStatus()).isEqualTo("PENDING");

        stub.on("GET", "/api/v1/access-requests", (ex, body) ->
                StubServer.Response.json(200, "[{\"id\":9,\"status\":\"PENDING\"},{\"id\":8,\"status\":\"REJECTED\",\"decisionComment\":\"不需要\"}]"));
        List<AccessRequestView> list = client.myAccessRequests("u1");
        assertThat(list).hasSize(2);
        assertThat(list.get(1).getDecisionComment()).isEqualTo("不需要");

        stub.on("POST", "/api/v1/access-requests/9/cancel", (ex, body) -> StubServer.Response.json(200, "{\"ok\":true}"));
        client.cancelAccessRequest(9L, "u1");

        stub.on("GET", "/api/v1/users/access", (ex, body) -> {
            assertThat(ex.getRequestURI().getQuery()).contains("externalId=u1").doesNotContain("departmentId");
            return StubServer.Response.json(200, "{\"userId\":1,\"roles\":[{\"id\":1,\"enabled\":true}],\"permissions\":[\"item:read\"]}");
        });
        UserAccessView access = client.userAccess("u1");
        assertThat(access.getRoles().get(0).getEnabled()).isTrue();
    }

    @Test
    void contextInvalidThrowsSubclass() {
        stub.on("GET", "/api/v1/contexts/effective", (ex, body) ->
                StubServer.Response.json(401, "{\"code\":\"CONTEXT_INVALID\",\"message\":\"已失效\",\"requestId\":\"r1\"}"));
        assertThatThrownBy(() -> client.effective("tok"))
                .isInstanceOfSatisfying(ContextInvalidException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(401);
                    assertThat(e.getCode()).isEqualTo("CONTEXT_INVALID");
                    assertThat(e.getRequestId()).isEqualTo("r1");
                });
    }

    @Test
    void apiErrorCarriesCodeAndRequestId() {
        stub.on("POST", "/api/v1/contexts", (ex, body) ->
                StubServer.Response.json(404, "{\"code\":\"NOT_FOUND\",\"message\":\"用户不存在\",\"requestId\":\"r2\"}"));
        assertThatThrownBy(() -> client.issueContext("ghost", null))
                .isInstanceOfSatisfying(MelonHubApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("NOT_FOUND");
                })
                .isNotInstanceOf(ContextInvalidException.class);
    }

    @Test
    void retriesAfterRetryAfterHeaderThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        stub.on("GET", "/api/v1/contexts/effective", (ex, body) -> {
            if (calls.incrementAndGet() == 1) {
                return new StubServer.Response(429, "{\"code\":\"RATE_LIMITED\",\"message\":\"慢点\"}", Map.of("Retry-After", "0"));
            }
            return StubServer.Response.json(200, "{\"userId\":1}");
        });
        long start = System.nanoTime();
        assertThat(client.effective("tok").getUserId()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(System.nanoTime() - start).isLessThan(Duration.ofSeconds(5).toNanos());
    }

    @Test
    void retriesExhaustedThrows() {
        stub.on("GET", "/api/v1/contexts/effective", (ex, body) ->
                new StubServer.Response(503, "{\"code\":\"OVERLOADED\",\"message\":\"过载\"}", Map.of("Retry-After", "0")));
        assertThatThrownBy(() -> client.effective("tok"))
                .isInstanceOfSatisfying(MelonHubApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo("OVERLOADED");
                });
        assertThat(stub.hits("GET", "/api/v1/contexts/effective")).isEqualTo(3); // 1 + 2 次重试
    }

    @Test
    void networkFailureWrapsInMelonHubException() {
        stub.close();
        assertThatThrownBy(() -> client.effective("tok")).isInstanceOf(MelonHubException.class);
    }
}
