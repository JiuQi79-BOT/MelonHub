package com.melonhub.rbac.r16;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.admin.dto.AccountCreateDTO;
import com.melonhub.rbac.api.admin.dto.AccountUpdateDTO;
import com.melonhub.rbac.api.admin.dto.LoginDTO;
import com.melonhub.rbac.api.admin.dto.PasswordChangeDTO;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminAccountRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminSessionRepository;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * R16 管理员登录安全、5 次失败锁定、停用拒绝、会话生命周期与 72 字节密码上限 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R16_AdminAuthControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、边界密码 12/72 字节、超限 75 字节 UTF-8 与预期错误码）
    // =========================================================================
    private static final String PATH_LOGIN = "/api/auth/login";
    private static final String PATH_ME = "/api/auth/me";
    private static final String PATH_LOGOUT = "/api/auth/logout";
    private static final String PATH_PASSWORD = "/api/auth/password";
    private static final String PATH_ACCOUNT_BY_ID = "/api/admin/accounts/{id}";

    private static final String UNKNOWN_USER = "ghost.admin.notfound";
    private static final String WRONG_PASSWORD = "WrongAdminPass!2026";
    private static final String NEW_PASSWORD = "RotatedAdminPass!2026";
    private static final String BOUNDARY_PASS_12 = "Abcd1234!@#$";
    private static final String BOUNDARY_PASS_72 = "P".repeat(72);
    private static final String INVALID_PASS_11 = "Abcd1234!@#";
    private static final String INVALID_PASS_75_UTF8 = "密".repeat(25); // 75 UTF-8 bytes > 72
    private static final int MAX_LOGIN_FAILURES = 5;

    private static final String ERR_BAD_CREDENTIALS = "BAD_CREDENTIALS";
    private static final String ERR_ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    private static final String ERR_ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_UNAUTHORIZED = "UNAUTHORIZED";
    private static final int IDLE_TIMEOUT_EXCEEDED_MINUTES = 31;

    @Autowired AdminAccountRepository adminAccountRepository;
    @Autowired AdminSessionRepository adminSessionRepository;

    @Test
    @DisplayName("R16-01 管理员登录成功、未知账号401、连续5次失败锁定403 ACCOUNT_LOCKED、停用账号403 ACCOUNT_DISABLED、非法DTO 400")
    void loginLockoutDisabledAndValidation() throws Exception {
        var acc = createAdminAndLogin("auth", AdminPrincipal.ADMIN, AdminPrincipal.MANAGER, null, null);
        var disAcc = createAdminAndLogin("dis", AdminPrincipal.ADMIN, AdminPrincipal.MANAGER, null, null);

        // 1. 正常登录返回 token + 会话有效期 + 空闲超时
        mvc.perform(post(PATH_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginDTO(acc.username(), TEST_ADMIN_PASSWORD, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(28800))
                .andExpect(jsonPath("$.idleTimeout").value(1800));

        // 2. 未知账号 -> 401 BAD_CREDENTIALS；非法空用户名 -> 400
        mvc.perform(post(PATH_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginDTO(UNKNOWN_USER, TEST_ADMIN_PASSWORD, null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_BAD_CREDENTIALS));
        mvc.perform(post(PATH_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginDTO(" ", TEST_ADMIN_PASSWORD, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));

        // 3. 停用账号登录 -> 403 ACCOUNT_DISABLED
        mvc.perform(put(PATH_ACCOUNT_BY_ID, disAcc.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountUpdateDTO("已停用", AdminPrincipal.ADMIN, false, null, true, null, null,
                                AdminPrincipal.MANAGER, true, null))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginDTO(disAcc.username(), TEST_ADMIN_PASSWORD, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_ACCOUNT_DISABLED));

        // 4. 连续 5 次密码错误：前 4 次 401 BAD_CREDENTIALS，第 5 次锁定 403 ACCOUNT_LOCKED，锁定期内正确密码亦 403
        for (int i = 1; i < MAX_LOGIN_FAILURES; i++) {
            mvc.perform(post(PATH_LOGIN).contentType(MediaType.APPLICATION_JSON)
                            .content(json(new LoginDTO(acc.username(), WRONG_PASSWORD, null))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ERR_BAD_CREDENTIALS));
        }
        mvc.perform(post(PATH_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginDTO(acc.username(), WRONG_PASSWORD, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_ACCOUNT_LOCKED));
        mvc.perform(post(PATH_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginDTO(acc.username(), TEST_ADMIN_PASSWORD, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_ACCOUNT_LOCKED));
    }

    @Test
    @DisplayName("R16-02 /api/auth/me、修改密码（12/72字节边界、超72字节UTF-8拒绝、同旧密拒绝、保留当前会话并踢掉其他会话）与退出登录")
    void meChangePasswordBoundariesSessionRotationAndLogout() throws Exception {
        var acc = createAdminAndLogin("pwd", AdminPrincipal.ADMIN, AdminPrincipal.MANAGER, null, null);
        String otherSession = loginBearer(acc.username(), TEST_ADMIN_PASSWORD);

        // 1. /me 正常与无令牌 401
        mvc.perform(get(PATH_ME).header("Authorization", acc.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(acc.username()));
        mvc.perform(get(PATH_ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_UNAUTHORIZED));

        // 2. 改密：保留当前会话，踢掉 otherSession
        mvc.perform(put(PATH_PASSWORD).header("Authorization", acc.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PasswordChangeDTO(TEST_ADMIN_PASSWORD, NEW_PASSWORD))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ME).header("Authorization", acc.bearer())).andExpect(status().isOk());
        mvc.perform(get(PATH_ME).header("Authorization", otherSession)).andExpect(status().isUnauthorized());

        // 3. 边界密码：12 字符 & 72 ASCII 字节均成功
        mvc.perform(put(PATH_PASSWORD).header("Authorization", acc.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PasswordChangeDTO(NEW_PASSWORD, BOUNDARY_PASS_12))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_PASSWORD).header("Authorization", acc.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PasswordChangeDTO(BOUNDARY_PASS_12, BOUNDARY_PASS_72))))
                .andExpect(status().isOk());

        // 4. 非法改密：11字符过短 400、75 UTF-8字节中文超限 400、同旧密 400、原密码错 400
        for (PasswordChangeDTO bad : List.of(
                new PasswordChangeDTO(BOUNDARY_PASS_72, INVALID_PASS_11),
                new PasswordChangeDTO(BOUNDARY_PASS_72, INVALID_PASS_75_UTF8),
                new PasswordChangeDTO(BOUNDARY_PASS_72, BOUNDARY_PASS_72),
                new PasswordChangeDTO(WRONG_PASSWORD, NEW_PASSWORD)
        )) {
            mvc.perform(put(PATH_PASSWORD).header("Authorization", acc.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest());
        }

        // 5. 退出登录后原令牌立即 401
        mvc.perform(post(PATH_LOGOUT).header("Authorization", acc.bearer()))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ME).header("Authorization", acc.bearer()))
                .andExpect(status().isUnauthorized());

        // 6. 会话空闲超 30 分钟后失效 401
        String idleSession = loginBearer(acc.username(), BOUNDARY_PASS_72);
        mvc.perform(get(PATH_ME).header("Authorization", idleSession)).andExpect(status().isOk());
        db.update("UPDATE admin_sessions SET last_seen_at = TIMESTAMPADD(MINUTE, -?, CURRENT_TIMESTAMP) WHERE account_id = ?",
                IDLE_TIMEOUT_EXCEEDED_MINUTES, acc.id());
        mvc.perform(get(PATH_ME).header("Authorization", idleSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_UNAUTHORIZED));

        // 7. 条件建会话防并发凭据变更与删号同名重建：版本号/密码哈希不匹配及已删旧 ID 均拒绝插入会话
        var cred = adminAccountRepository.credentials(acc.id()).orElseThrow();
        Instant expires = Instant.now().plus(1, ChronoUnit.HOURS);
        assertThat(adminAccountRepository.createSessionIfCredentialsMatch(
                adminSessionRepository, Credentials.hash(Credentials.token()), acc.id(), cred.getCredentialVersion() + 1,
                cred.getPasswordHash(), cred.getMfaSecret(), expires, "127.0.0.1")).isZero();
        assertThat(adminAccountRepository.createSessionIfCredentialsMatch(
                adminSessionRepository, Credentials.hash(Credentials.token()), acc.id(), cred.getCredentialVersion(),
                "stale-hash", cred.getMfaSecret(), expires, "127.0.0.1")).isZero();

        mvc.perform(delete(PATH_ACCOUNT_BY_ID, acc.id()).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_ADMIN_ACCOUNTS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountCreateDTO(acc.username(), "同名重建", AdminPrincipal.ADMIN,
                                TEST_ADMIN_PASSWORD, true, null))))
                .andExpect(status().isOk());
        assertThat(adminAccountRepository.createSessionIfCredentialsMatch(
                adminSessionRepository, Credentials.hash(Credentials.token()), acc.id(), cred.getCredentialVersion(),
                cred.getPasswordHash(), cred.getMfaSecret(), expires, "127.0.0.1")).isZero();
    }
}
