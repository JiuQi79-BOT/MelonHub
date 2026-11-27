package com.melonhub.rbac.r14;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.melonhub.rbac.infrastructure.cache.PermissionCache;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import com.melonhub.rbac.service.port.application.ApplicationModels.SaveApplicationCommand;
import com.melonhub.rbac.service.port.application.ApplicationUseCase;
import com.melonhub.rbac.service.port.context.ContextUseCase;
import com.melonhub.rbac.service.port.context.IssueContextCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.BatchUserRolesCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SavePermissionCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SaveRoleCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacUseCase;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.governance.GovernanceUseCase;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveDepartmentCommand;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveUserCommand;
import com.melonhub.rbac.service.port.organization.OrganizationUseCase;
import com.melonhub.rbac.service.port.webhook.WebhookModels.SaveWebhookCommand;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import com.melonhub.rbac.shared.CloudTestDatabaseGuard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * 真实事务提交 + Redis 代标记协议端到端专项集成测试（不加 {@code @Transactional}，测试后清理全部数据）。
 * 对应需求：R3、R4、R14、R28（见 doc/requirements.md）。
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = CloudTestDatabaseGuard.class)
class R14_R28_PermissionCacheIntegrationTest {
    // 统一测试参数区
    private static final String ACTOR = "cache-test";
    private static final String PERM_READ = "item:read";
    private static final String PERM_WRITE = "item:write";
    private static final String CACHE_METRIC = "melonhub.cache.lookups";
    private static final String EXPIRY_WEBHOOK_URL = "http://127.0.0.1:1/hook";
    private static final String EVENT_ROLES_EXPIRED = "user.roles.expired";
    private static final String AUDIT_ROLES_EXPIRED = "USER_ROLES_EXPIRED";
    private static final int EXPIRY_DELAY_SECONDS = 3;
    private static final long EXPIRY_GRACE_MS = 300L;
    private static final int EXPIRE_BATCH_SIZE = 500;
    private static final int REPEATED_LOOKUP_COUNT = 3;

    @Autowired OrganizationUseCase orgs;
    @Autowired ApplicationUseCase apps;
    @Autowired DomainRbacUseCase rbac;
    @Autowired ContextUseCase c;
    @Autowired AuthUseCase auth;
    @Autowired GovernanceUseCase g;
    @Autowired WebhookUseCase webhooks;
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
        d1 = orgs.department(new SaveDepartmentCommand("Cache One", "c1-" + suffix), null, ACTOR);
        d2 = orgs.department(new SaveDepartmentCommand("Cache Two", "c2-" + suffix), null, ACTOR);
        var app = apps.application(new SaveApplicationCommand("Cache App", "ca-" + suffix, ""), ACTOR);
        a1 = app.id();
        key = app.apiKey();
        u1 = orgs.user(new SaveUserCommand("Cache User", external, "", List.of(d1, d2)), null, ACTOR);
        long p1 = rbac.permission(new SavePermissionCommand("Read", PERM_READ, "item", "read"), null, d1, a1, ACTOR);
        long p2 = rbac.permission(new SavePermissionCommand("Write", PERM_WRITE, "item", "write"), null, d1, a1, ACTOR);
        long pOther = rbac.permission(new SavePermissionCommand("Read", PERM_READ, "item", "read"), null, d2, a1, ACTOR);
        long r1 = rbac.role(new SaveRoleCommand("Reader", "reader", ""), null, d1, a1, ACTOR);
        r2 = rbac.role(new SaveRoleCommand("Writer", "writer", ""), null, d1, a1, ACTOR);
        long rOther = rbac.role(new SaveRoleCommand("Reader", "reader", ""), null, d2, a1, ACTOR);
        rbac.rolePermissions(r1, d1, a1, List.of(p1), ACTOR);
        rbac.rolePermissions(r2, d1, a1, List.of(p2), ACTOR);
        rbac.rolePermissions(rOther, d2, a1, List.of(pOther), ACTOR);
        rbac.userRoles(u1, d1, a1, List.of(r1, r2), ACTOR);
        rbac.userRoles(u1, d2, a1, List.of(rOther), ACTOR);
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

    private String issue(long dept) { return c.issueContext(a1, new IssueContextCommand(external, dept, null), ACTOR).contextToken(); }

    private double count(String kind, String result) {
        Counter counter = metrics.find(CACHE_METRIC).tags("kind", kind, "result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @DisplayName("R28 重复校验命中 Redis 缓存且撤权后立即失效")
    @Test void repeatedChecksAreServedFromRedisAndRevocationIsImmediate() {
        String token = issue(d1);
        double hits = count("context", "hit");
        for (int i = 0; i < REPEATED_LOOKUP_COUNT; i++) assertThat(c.check(a1, token, List.of(PERM_WRITE)).allowed()).isTrue();
        assertThat(count("context", "hit") - hits).isGreaterThanOrEqualTo(2);
        rbac.rolePermissions(r2, d1, a1, List.of(), ACTOR);
        assertThat(c.check(a1, token, List.of(PERM_WRITE)).allowed()).isFalse();
        assertThat(c.check(a1, token, List.of(PERM_READ)).allowed()).isTrue();
    }

    @DisplayName("R28 部门切换与停用用户不受旧缓存影响立即生效")
    @Test void departmentSwitchAndUserDisableApplyDespiteCachedDecisions() {
        String token = issue(d1);
        c.effective(a1, token);
        c.effective(a1, token);
        String next = issue(d2);
        assertThatThrownBy(() -> c.effective(a1, token)).isInstanceOf(ApiException.class);
        assertThat(c.effective(a1, next).permissions()).isEqualTo(List.of(PERM_READ));
        double hits = count("context", "hit");
        c.effective(a1, next);
        c.effective(a1, next);
        assertThat(count("context", "hit") - hits).isGreaterThanOrEqualTo(1);
        orgs.userStatus(u1, false, ACTOR);
        assertThatThrownBy(() -> c.effective(a1, next)).isInstanceOf(ApiException.class);
    }

    @DisplayName("R14/R28 角色分配到期时刻缓存即时失效并广播到期事件")
    @Test void lapsedAssignmentEndsCachedDecisionsOnTimeAndIsAnnounced() throws Exception {
        webhooks.create(a1, new SaveWebhookCommand("expiry", EXPIRY_WEBHOOK_URL, null, List.of(EVENT_ROLES_EXPIRED), true), ACTOR);
        db.update("UPDATE user_roles SET expires_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP) WHERE user_id=? AND role_id=?",
                EXPIRY_DELAY_SECONDS, u1, r2);
        Instant lapse = db.queryForObject("SELECT expires_at FROM user_roles WHERE user_id=? AND role_id=?", Timestamp.class, u1, r2).toInstant();
        String token = issue(d1);
        double hits = count("context", "hit");
        assertThat(c.check(a1, token, List.of(PERM_WRITE)).allowed()).isTrue();
        assertThat(c.check(a1, token, List.of(PERM_WRITE)).allowed()).isTrue();
        assertThat(count("context", "hit") - hits).isGreaterThanOrEqualTo(1);
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), lapse).toMillis()) + EXPIRY_GRACE_MS);
        // Nothing was written, yet the cached decision does not outlive the assignment.
        assertThat(c.check(a1, token, List.of(PERM_WRITE)).allowed()).isFalse();
        assertThat(c.check(a1, token, List.of(PERM_READ)).allowed()).isTrue();
        assertThat(g.expireAssignments(EXPIRE_BATCH_SIZE)).isGreaterThanOrEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id=? AND role_id=?", Long.class, u1, r2)).isZero();
        String payload = db.queryForObject("SELECT d.payload FROM webhook_deliveries d JOIN webhooks w ON w.id=d.webhook_id "
                + "WHERE w.application_id=? AND d.event_type=?", String.class, a1, EVENT_ROLES_EXPIRED);
        assertThat(payload).contains(external).contains("\"roleIds\":[" + r2 + "]");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action=? AND target=?", Long.class, AUDIT_ROLES_EXPIRED, external)).isEqualTo(1);
        // A second run finds nothing left to announce.
        g.expireAssignments(EXPIRE_BATCH_SIZE);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action=? AND target=?", Long.class, AUDIT_ROLES_EXPIRED, external)).isEqualTo(1);
    }

    @DisplayName("R28 批量授角失效旧缓存且新上下文包含新增角色")
    @Test void batchGrantInvalidatesCachedContextAndNewContextIncludesTheRole() {
        rbac.userRoles(u1, d1, a1, List.of(), ACTOR);
        String token = issue(d1);
        assertThat(c.check(a1, token, List.of(PERM_WRITE)).allowed()).isFalse();
        c.effective(a1, token);
        rbac.addUserRoles(d1, a1, new BatchUserRolesCommand(List.of(u1), List.of(r2), null), ACTOR);
        assertThatThrownBy(() -> c.effective(a1, token)).isInstanceOf(ApiException.class);
        assertThat(c.check(a1, issue(d1), List.of(PERM_WRITE)).allowed()).isTrue();
    }

    @DisplayName("R3/R4/R28 吊销密钥或停用软件后缓存密钥立即失效")
    @Test void revokedOrDisabledApiKeysStopWorkingImmediately() {
        double hits = count("apikey", "hit");
        for (int i = 0; i < REPEATED_LOOKUP_COUNT; i++) assertThat(auth.application(key).applicationId()).isEqualTo(a1);
        assertThat(count("apikey", "hit") - hits).isGreaterThanOrEqualTo(1);
        apps.applicationStatus(a1, false, ACTOR);
        assertThatThrownBy(() -> auth.application(key)).isInstanceOf(ApiException.class);
        apps.applicationStatus(a1, true, ACTOR);
        assertThat(auth.application(key).applicationId()).isEqualTo(a1);
        assertThat(auth.application(key).applicationId()).isEqualTo(a1);
        long id = apps.keys(a1).get(0).id();
        apps.revokeKey(a1, id, ACTOR);
        assertThatThrownBy(() -> auth.application(key)).isInstanceOf(ApiException.class);
    }
}
