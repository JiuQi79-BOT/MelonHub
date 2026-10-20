package com.melonhub.rbac;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.melonhub.rbac.cache.PermissionCache;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.Credentials;
import com.melonhub.rbac.service.AuthService;
import com.melonhub.rbac.service.ContextService;
import com.melonhub.rbac.service.GovernanceService;
import com.melonhub.rbac.service.RbacService;
import com.melonhub.rbac.service.WebhookService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * Runs without a test transaction so writes really commit and the Redis generation protocol is exercised end
 * to end. All rows it creates are removed afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers=CloudTestDatabaseGuard.class)
class PermissionCacheIntegrationTest {
    private static final String ACTOR = "cache-test";
    @Autowired RbacService r;
    @Autowired ContextService c;
    @Autowired AuthService auth;
    @Autowired GovernanceService g;
    @Autowired WebhookService webhooks;
    @Autowired PermissionCache cache;
    @Autowired MeterRegistry metrics;
    @Autowired JdbcTemplate db;
    long d1, d2, a1, u1, r2;
    String external, key;

    @BeforeEach void fixture() {
        assumeFalse("DISABLED".equals(cache.health()), "Redis cache is disabled by configuration");
        assertThat(cache.health()).as("Redis must be running for cache tests").isEqualTo("UP");
        String suffix = Credentials.token().substring(0, 12);
        external = "cache-" + suffix;
        d1 = r.department(new Requests.Department("Cache One", "c1-" + suffix), null, ACTOR);
        d2 = r.department(new Requests.Department("Cache Two", "c2-" + suffix), null, ACTOR);
        var app = r.application(new Requests.Application("Cache App", "ca-" + suffix, ""), ACTOR);
        a1 = ((Number) app.get("id")).longValue();
        key = (String) app.get("apiKey");
        u1 = r.user(new Requests.User("Cache User", external, "", List.of(d1, d2)), null, ACTOR);
        long r1 = r.role(new Requests.Role("Reader", "reader", ""), null, d1, a1, ACTOR);
        r2 = r.role(new Requests.Role("Writer", "writer", ""), null, d1, a1, ACTOR);
        long rOther = r.role(new Requests.Role("Reader", "reader-other", ""), null, d2, a1, ACTOR);
        long p1 = r.permission(new Requests.Permission(r1, "Read", "item:read", "item", "read"), null, d1, a1, ACTOR);
        long p2 = r.permission(new Requests.Permission(r2, "Write", "item:write", "item", "write"), null, d1, a1, ACTOR);
        long pOther = r.permission(new Requests.Permission(rOther, "Read", "item:read", "item", "read"), null, d2, a1, ACTOR);
        r.rolePermissions(r1, d1, a1, List.of(p1), ACTOR);
        r.rolePermissions(r2, d1, a1, List.of(p2), ACTOR);
        r.rolePermissions(rOther, d2, a1, List.of(pOther), ACTOR);
        r.userRoles(u1, d1, a1, List.of(r1, r2, rOther), ACTOR);

    }

    @AfterEach void cleanup() {
        db.update("DELETE FROM users WHERE id=?", u1);
        db.update("DELETE FROM permission_contexts WHERE application_id=?", a1);
        db.update("DELETE FROM roles WHERE application_id=?", a1);
        db.update("DELETE FROM permissions WHERE application_id=?", a1);
        db.update("DELETE FROM applications WHERE id=?", a1);
        db.update("DELETE FROM departments WHERE id IN (?,?)", d1, d2);
        db.update("DELETE FROM audit_events WHERE actor=? OR application_id=?", ACTOR, a1);
    }

    private String issue(long dept) { return (String) c.issue(a1, new Requests.Context(external, dept, null), ACTOR).get("contextToken"); }
    private double count(String kind, String result) {
        Counter counter = metrics.find("melonhub.cache.lookups").tags("kind", kind, "result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test void repeatedChecksAreServedFromRedisAndRevocationIsImmediate() {
        String token = issue(d1);
        double hits = count("context", "hit");
        for (int i = 0; i < 3; i++) assertThat(c.check(a1, token, List.of("item:write")).get("allowed")).isEqualTo(true);
        assertThat(count("context", "hit") - hits).isGreaterThanOrEqualTo(2);
        r.rolePermissions(r2, d1, a1, List.of(), ACTOR);
        assertThat(c.check(a1, token, List.of("item:write")).get("allowed")).isEqualTo(false);
        assertThat(c.check(a1, token, List.of("item:read")).get("allowed")).isEqualTo(true);
    }

    @Test void departmentFilterPreservesCachedContextsAndRoleChangesInvalidateAcrossFilters() {
        String token = issue(d1);
        c.effective(a1, token);
        c.effective(a1, token);
        String next = issue(d2);
        assertThat(c.effective(a1, token).get("permissions")).isEqualTo(List.of("item:read", "item:write"));
        assertThat(c.effective(a1, next).get("permissions")).isEqualTo(List.of("item:read", "item:write"));
        r.rolePermissions(r2, d1, a1, List.of(), ACTOR);
        assertThat(c.check(a1, next, List.of("item:write")).get("allowed")).isEqualTo(false);
        assertThat(c.check(a1, token, List.of("item:write")).get("allowed")).isEqualTo(false);
        double hits = count("context", "hit");
        c.effective(a1, next);
        c.effective(a1, next);
        assertThat(count("context", "hit") - hits).isGreaterThanOrEqualTo(1);
        r.userStatus(u1, false, ACTOR);
        assertThatThrownBy(() -> c.effective(a1, next)).isInstanceOf(ApiException.class);
    }

    @Test void lapsedAssignmentEndsCachedDecisionsOnTimeAndIsAnnounced() throws Exception {
        webhooks.create(a1, new Requests.Webhook("expiry", "http://127.0.0.1:1/hook", null, List.of("user.roles.expired"), true), ACTOR);
        db.update("UPDATE user_roles SET expires_at=TIMESTAMPADD(SECOND,3,CURRENT_TIMESTAMP) WHERE user_id=? AND role_id=?", u1, r2);
        Instant lapse = db.queryForObject("SELECT expires_at FROM user_roles WHERE user_id=? AND role_id=?", Timestamp.class, u1, r2).toInstant();
        String token = issue(d1);
        double hits = count("context", "hit");
        assertThat(c.check(a1, token, List.of("item:write")).get("allowed")).isEqualTo(true);
        assertThat(c.check(a1, token, List.of("item:write")).get("allowed")).isEqualTo(true);
        assertThat(count("context", "hit") - hits).isGreaterThanOrEqualTo(1);
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), lapse).toMillis()) + 300);
        // Nothing was written, yet the cached decision does not outlive the assignment.
        assertThat(c.check(a1, token, List.of("item:write")).get("allowed")).isEqualTo(false);
        assertThat(c.check(a1, token, List.of("item:read")).get("allowed")).isEqualTo(true);
        assertThat(g.expireAssignments(500)).isGreaterThanOrEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id=? AND role_id=?", Long.class, u1, r2)).isZero();
        String payload = db.queryForObject("SELECT d.payload FROM webhook_deliveries d JOIN webhooks w ON w.id=d.webhook_id "
                + "WHERE w.application_id=? AND d.event_type='user.roles.expired'", String.class, a1);
        assertThat(payload).contains(external).contains("\"roleIds\":[" + r2 + "]");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='USER_ROLES_EXPIRED' AND target=?", Long.class, external)).isEqualTo(1);
        // A second run finds nothing left to announce.
        g.expireAssignments(500);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='USER_ROLES_EXPIRED' AND target=?", Long.class, external)).isEqualTo(1);
    }

    @Test void batchGrantInvalidatesCachedContextAndNewContextIncludesTheRole() {
        r.userRoles(u1, d1, a1, List.of(), ACTOR);
        String token = issue(d1);
        assertThat(c.check(a1, token, List.of("item:write")).get("allowed")).isEqualTo(false);
        c.effective(a1, token);
        r.addUserRoles(d1, a1, new Requests.BatchUserRoles(List.of(u1), List.of(r2), null), ACTOR);
        assertThatThrownBy(() -> c.effective(a1, token)).isInstanceOf(ApiException.class);
        assertThat(c.check(a1, issue(d1), List.of("item:write")).get("allowed")).isEqualTo(true);
    }

    @Test void revokedOrDisabledApiKeysStopWorkingImmediately() {
        double hits = count("apikey", "hit");
        for (int i = 0; i < 3; i++) assertThat(auth.application(key).applicationId()).isEqualTo(a1);
        assertThat(count("apikey", "hit") - hits).isGreaterThanOrEqualTo(1);
        r.applicationStatus(a1, false, ACTOR);
        assertThatThrownBy(() -> auth.application(key)).isInstanceOf(ApiException.class);
        r.applicationStatus(a1, true, ACTOR);
        assertThat(auth.application(key).applicationId()).isEqualTo(a1);
        assertThat(auth.application(key).applicationId()).isEqualTo(a1);
        long id = ((Number) r.keys(a1).get(0).get("id")).longValue();
        r.revokeKey(a1, id, ACTOR);
        assertThatThrownBy(() -> auth.application(key)).isInstanceOf(ApiException.class);
    }
}
