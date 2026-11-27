package com.melonhub.rbac.r06;

import static org.assertj.core.api.Assertions.assertThat;

import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.service.port.application.ApplicationModels.SaveApplicationCommand;
import com.melonhub.rbac.service.port.application.ApplicationUseCase;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacModels.SaveRoleCommand;
import com.melonhub.rbac.service.port.domainrbac.DomainRbacUseCase;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveDepartmentCommand;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveUserCommand;
import com.melonhub.rbac.service.port.organization.OrganizationUseCase;
import com.melonhub.rbac.shared.CloudTestDatabaseGuard;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 真实多连接并发测试（不加 {@code @Transactional}），验证角色成员替换遭遇晚到成员时回滚重排且无死锁。
 * 对应需求：R6（见 doc/requirements.md）。
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = CloudTestDatabaseGuard.class)
class R06_RoleMembersConcurrencyIntegrationTest {
    // 统一测试参数区
    private static final String ACTOR = "race-test";
    private static final String DEPT_NAME = "Race Dept";
    private static final String APP_NAME = "Race App";
    private static final String STAY_USER_NAME = "Stays";
    private static final String LATE_USER_NAME = "Joins late";
    private static final String ROLE_NAME = "Race";
    private static final String ROLE_CODE = "race";
    private static final int WORKER_THREADS = 3;
    private static final long LOCK_SETTLE_MS = 500L;
    private static final long JOIN_WAIT_SECONDS = 10L;
    private static final long COMMIT_HOLD_SECONDS = 30L;
    private static final long FUTURE_WAIT_SECONDS = 20L;

    @Autowired OrganizationUseCase orgs;
    @Autowired ApplicationUseCase apps;
    @Autowired DomainRbacUseCase rbac;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;

    /**
     * A user who joins the role while a replacement is waiting for the role lock is only seen by the replacement's
     * locking read. Meanwhile a change to that user holds the user and waits for the replacement's assignment
     * locks; locking the user at that point would close a cycle, so the replacement starts over instead.
     */
    @DisplayName("R6 角色成员替换遇晚到成员回滚重排不发生死锁")
    @Test void aMemberWhoJoinsDuringAReplacementDoesNotDeadlockIt() throws Exception {
        String suffix = Credentials.token().substring(0, 12);
        long dept = -1, app = -1, stay = -1, late = -1;
        var pool = Executors.newFixedThreadPool(WORKER_THREADS);
        try {
            dept = orgs.department(new SaveDepartmentCommand(DEPT_NAME, "race-d-" + suffix), null, ACTOR);
            app = apps.application(new SaveApplicationCommand(APP_NAME, "race-a-" + suffix, ""), ACTOR).id();
            stay = orgs.user(new SaveUserCommand(STAY_USER_NAME, "race-stay-" + suffix, "", List.of(dept)), null, ACTOR);
            late = orgs.user(new SaveUserCommand(LATE_USER_NAME, "race-late-" + suffix, "", List.of(dept)), null, ACTOR);
            long role = rbac.role(new SaveRoleCommand(ROLE_NAME, ROLE_CODE, ""), null, dept, app, ACTOR);
            long d = dept, a = app, member = stay, joiner = late;
            var joined = new CountDownLatch(1);
            var commit = new CountDownLatch(1);
            var tx = new TransactionTemplate(transactions);
            Future<?> joining = pool.submit(() -> tx.executeWithoutResult(status -> {
                db.queryForObject("SELECT id FROM roles WHERE id=? FOR UPDATE", Long.class, role);
                db.update("INSERT INTO user_roles(user_id,role_id,department_id,application_id) VALUES (?,?,?,?)", joiner, role, d, a);
                joined.countDown();
                try { commit.await(COMMIT_HOLD_SECONDS, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
            assertThat(joined.await(JOIN_WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<?> replacing = pool.submit(() -> rbac.roleMembers(role, d, a, List.of(member), ACTOR));
            Thread.sleep(LOCK_SETTLE_MS);
            Future<?> changing = pool.submit(() -> rbac.userRoles(joiner, d, a, List.of(role), ACTOR));
            Thread.sleep(LOCK_SETTLE_MS);
            assertThat(replacing.isDone()).as("the replacement waits for the role").isFalse();
            assertThat(changing.isDone()).as("the change to the late member waits for its assignments").isFalse();
            commit.countDown();
            joining.get(JOIN_WAIT_SECONDS, TimeUnit.SECONDS);
            changing.get(FUTURE_WAIT_SECONDS, TimeUnit.SECONDS);
            replacing.get(FUTURE_WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(db.queryForList("SELECT user_id FROM user_roles WHERE role_id=?", Long.class, role)).containsExactly(stay);
        } finally {
            pool.shutdownNow();
            db.update("DELETE FROM users WHERE id IN (?,?)", stay, late);
            if (app > 0) {
                db.update("DELETE FROM roles WHERE application_id=?", app);
                db.update("DELETE FROM applications WHERE id=?", app);
            }
            if (dept > 0) db.update("DELETE FROM departments WHERE id=?", dept);
            db.update("DELETE FROM audit_events WHERE actor=? OR application_id=?", ACTOR, app);
        }
    }
}
