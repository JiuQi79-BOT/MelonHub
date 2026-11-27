package com.melonhub.rbac.r17;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.melonhub.rbac.api.admin.dto.LoginDTO;
import com.melonhub.rbac.api.admin.dto.MfaCodeDTO;
import com.melonhub.rbac.api.admin.dto.MfaDisableDTO;
import com.melonhub.rbac.infrastructure.security.Totp;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R17 管理员 RFC 6238 TOTP 两步验证（绑定/确认/防重放/停用/超管重置） —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R17_AdminMfaControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、非法动态口令、错误密码与预期 MFA 错误码）
    // =========================================================================
    private static final String PATH_LOGIN = "/api/auth/login";
    private static final String PATH_ME = "/api/auth/me";
    private static final String PATH_MFA_SETUP = "/api/auth/mfa/setup";
    private static final String PATH_MFA_CONFIRM = "/api/auth/mfa/confirm";
    private static final String PATH_MFA_DISABLE = "/api/auth/mfa/disable";
    private static final String PATH_MFA_RESET = "/api/admin/accounts/{id}/mfa/reset";

    private static final String INVALID_CODE_FORMAT = "12ab";
    private static final String WRONG_CODE_6_DIGITS = "000000";
    private static final String WRONG_PASSWORD = "WrongPassword!2026";

    private static final String ERR_MFA_REQUIRED = "MFA_REQUIRED";
    private static final String ERR_MFA_INVALID = "MFA_INVALID";
    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";

    @Test
    @DisplayName("R17-01 MFA 全生命周期：setup -> confirm(踢旧会话) -> 登录需口令/防同一步重放 -> disable(需密码+新步口令)")
    void mfaSetupConfirmLoginReplayDefenseAndDisable() throws Exception {
        var acc = createAdminAndLogin("mfa", AdminPrincipal.ADMIN, AdminPrincipal.MANAGER, null, null);
        String otherSession = loginBearer(acc.username(), TEST_ADMIN_PASSWORD);

        // 1. Setup 生成 Base32 密钥与 otpauth:// URI
        JsonNode setup = parseJson(mvc.perform(post(PATH_MFA_SETUP).header("Authorization", acc.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.uri").isNotEmpty())
                .andReturn().getResponse().getContentAsString());
        String secret = setup.get("secret").asText();

        // 2. Confirm：格式非法 400、错误码 400 MFA_INVALID、正确当前步口令 -> 200 且踢掉 otherSession
        mvc.perform(post(PATH_MFA_CONFIRM).header("Authorization", acc.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new MfaCodeDTO(INVALID_CODE_FORMAT))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        long confirmStep = Totp.currentStep();
        String stepNow = Totp.codeAt(secret, confirmStep);
        String stepPlus1 = Totp.codeAt(secret, confirmStep + 1);
        String stepMinus1 = Totp.codeAt(secret, confirmStep - 1);
        String wrongCode = WRONG_CODE_6_DIGITS;
        for (String candidate : new String[]{WRONG_CODE_6_DIGITS, "111111", "222222", "333333"}) {
            if (!candidate.equals(stepMinus1) && !candidate.equals(stepNow) && !candidate.equals(stepPlus1)) {
                wrongCode = candidate;
                break;
            }
        }

        mvc.perform(post(PATH_MFA_CONFIRM).header("Authorization", acc.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new MfaCodeDTO(wrongCode))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_MFA_INVALID));
        mvc.perform(post(PATH_MFA_CONFIRM).header("Authorization", acc.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new MfaCodeDTO(stepNow))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ME).header("Authorization", otherSession))
                .andExpect(status().isUnauthorized());

        // 3. 登录：缺口令 -> 401 MFA_REQUIRED；重放 stepNow -> 401 MFA_INVALID；使用 stepPlus1 -> 200；再重放 stepPlus1 -> 401
        mvc.perform(post(PATH_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginDTO(acc.username(), TEST_ADMIN_PASSWORD, null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_MFA_REQUIRED));
        mvc.perform(post(PATH_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginDTO(acc.username(), TEST_ADMIN_PASSWORD, stepNow))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_MFA_INVALID));
        String loggedInBearer = loginBearer(acc.username(), TEST_ADMIN_PASSWORD, stepPlus1);
        mvc.perform(post(PATH_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginDTO(acc.username(), TEST_ADMIN_PASSWORD, stepPlus1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_MFA_INVALID));

        // 4. Disable：错密码 400、重放已消费的 stepPlus1 400 MFA_INVALID；回拨步数模拟进入下一时间步后用 stepPlus1 停用 -> 200
        mvc.perform(post(PATH_MFA_DISABLE).header("Authorization", loggedInBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new MfaDisableDTO(WRONG_PASSWORD, stepPlus1))))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH_MFA_DISABLE).header("Authorization", loggedInBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new MfaDisableDTO(TEST_ADMIN_PASSWORD, stepPlus1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_MFA_INVALID));
        db.update("UPDATE admin_accounts SET mfa_last_step = mfa_last_step - 1 WHERE id = ?", acc.id());
        mvc.perform(post(PATH_MFA_DISABLE).header("Authorization", loggedInBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new MfaDisableDTO(TEST_ADMIN_PASSWORD, stepPlus1))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginDTO(acc.username(), TEST_ADMIN_PASSWORD, null))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("R17-02 超级管理员重置他人两步验证成功且踢掉重置前的活跃 MFA 会话，普通审计员调用返回 403")
    void superAdminResetsMfaAndUnauthorizedRoleGets403() throws Exception {
        var target = createAdminAndLogin("mfarst", AdminPrincipal.ADMIN, AdminPrincipal.MANAGER, null, null);
        JsonNode setup = parseJson(mvc.perform(post(PATH_MFA_SETUP).header("Authorization", target.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String secret = setup.get("secret").asText();
        long step = Totp.currentStep();
        mvc.perform(post(PATH_MFA_CONFIRM).header("Authorization", target.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new MfaCodeDTO(Totp.codeAt(secret, step)))))
                .andExpect(status().isOk());
        // 确认绑定会撤销旧会话，需用下一窗口 MFA 动态口令建立新的活跃会话，再验证 resetMfa 真正撤销该活跃会话
        String activeMfaBearer = loginBearer(target.username(), TEST_ADMIN_PASSWORD, Totp.codeAt(secret, step + 1));
        mvc.perform(get(PATH_ME).header("Authorization", activeMfaBearer))
                .andExpect(status().isOk());

        // 非授权角色 -> 403
        var auditor = createAuditor();
        mvc.perform(post(PATH_MFA_RESET, target.id()).header("Authorization", auditor.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));

        // 超级管理员重置 -> 200，activeMfaBearer 立即失效，且无需 MFA 即可重新登录
        mvc.perform(post(PATH_MFA_RESET, target.id()).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ME).header("Authorization", activeMfaBearer))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginDTO(target.username(), TEST_ADMIN_PASSWORD, null))))
                .andExpect(status().isOk());
    }
}
