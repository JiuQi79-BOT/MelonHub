package com.melonhub.rbac.r17;

import static org.assertj.core.api.Assertions.assertThat;

import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.infrastructure.security.Totp;
import com.melonhub.rbac.service.port.admin.AdminModels.CreateAccountCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.LoginCommand;
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.shared.CloudTestDatabaseGuard;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * 真实多连接并发测试（不加 {@code @Transactional}），验证单步 TOTP 口令在并发登录下仅能成功使用一次。
 * 对应需求：R17（见 doc/requirements.md）。
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = CloudTestDatabaseGuard.class)
class R17_AdminTotpConcurrencyIntegrationTest {
    // 统一测试参数区
    private static final String ACTOR = "race-test";
    private static final String DISPLAY_NAME = "并发登录";
    private static final String ROLE_AUDITOR = "AUDITOR";
    private static final String PASSWORD = "mfa-password-123";
    private static final int CONCURRENT_ATTEMPTS = 4;
    private static final long FUTURE_WAIT_SECONDS = 30L;
    private static final String OUTCOME_OK = "OK";
    private static final String ERROR_MFA_INVALID = "MFA_INVALID";

    @Autowired AuthUseCase auth;
    @Value("${melonhub.admin-username}") String bootstrap;
    @Autowired JdbcTemplate db;

    @DisplayName("R17 多线程并发登录不能重复使用同一个 TOTP 动态口令")
    @Test void concurrentLoginsCannotShareOneTotpCode() throws Exception {
        String username = "mfa-race-" + Credentials.token().substring(0, 8);
        auth.createAccount(new CreateAccountCommand(username, DISPLAY_NAME, ROLE_AUDITOR, PASSWORD, true, null), auth.principal(bootstrap));
        var pool = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS);
        try {
            var principal = auth.principal(username);
            String secret = auth.setupMfa(principal).secret();
            long step = Totp.currentStep();
            auth.confirmMfa(principal, Totp.codeAt(secret, step));
            String code = Totp.codeAt(secret, step + 1);
            var start = new CountDownLatch(1);
            var attempts = new ArrayList<Future<String>>();
            for (int i = 0; i < CONCURRENT_ATTEMPTS; i++) attempts.add(pool.submit(() -> {
                start.await();
                try {
                    auth.login(new LoginCommand(username, PASSWORD, code));
                    return OUTCOME_OK;
                } catch (ApiException e) {
                    return e.code();
                }
            }));
            start.countDown();
            var outcomes = new ArrayList<String>();
            for (var attempt : attempts) outcomes.add(attempt.get(FUTURE_WAIT_SECONDS, TimeUnit.SECONDS));
            assertThat(outcomes).containsOnlyOnce(OUTCOME_OK);
            assertThat(outcomes.stream().filter(outcome -> !outcome.equals(OUTCOME_OK)).toList())
                    .hasSize(CONCURRENT_ATTEMPTS - 1).containsOnly(ERROR_MFA_INVALID);
        } finally {
            pool.shutdownNow();
            db.update("DELETE FROM admin_accounts WHERE username=?", username);
            db.update("DELETE FROM audit_events WHERE actor IN (?,?) OR target=?", ACTOR, username, username);
        }
    }
}
