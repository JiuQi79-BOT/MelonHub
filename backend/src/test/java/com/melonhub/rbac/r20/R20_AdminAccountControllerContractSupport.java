package com.melonhub.rbac.r20;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.admin.dto.AccountCreateDTO;
import com.melonhub.rbac.api.admin.dto.AccountUpdateDTO;
import com.melonhub.rbac.api.admin.dto.PasswordResetDTO;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R19 & R20 管理员层级树、双轴范围与管理员账号数字主键全生命周期 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R20_AdminAccountControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、账号名合法/非法样本、密码边界、不存在 ID 与预期错误码）
    // =========================================================================
    private static final String PATH_ACCOUNTS = "/api/admin/accounts";
    private static final String PATH_ACCOUNT_BY_ID = "/api/admin/accounts/{id}";
    private static final String PATH_ACCOUNT_UNLOCK = "/api/admin/accounts/{id}/unlock";
    private static final String PATH_ACCOUNT_PASSWORD = "/api/admin/accounts/{id}/password";
    private static final String PATH_ME = "/api/auth/me";

    private static final long MISSING_ACCOUNT_ID = 9_999_999L;
    private static final String INVALID_USERNAME_DOT = ".badstart";
    private static final String INVALID_USERNAME_SHORT = "ab";
    private static final String INVALID_PASS_SHORT = "short123";
    private static final String INVALID_PASS_75_UTF8 = "密".repeat(25);
    private static final String RESET_PASS = "ResetBySuperAdmin!2026";

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_CONFLICT = "CONFLICT";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";

    @Test
    @DisplayName("R19/R20-01 管理员账号增（含 @/. 合法名）/查/改（踢旧会话）/启停/解锁/重置密码/删（删后同名重建旧会话仍失效）与自删 400")
    void adminAccountFullLifecycleAndCredentialVersionProtection() throws Exception {
        long d1 = createDepartment("管辖部", unique("ad1"));
        String emailUser = "ops." + tag + "@company.com";

        // 1. 新建含 @ 和 . 的合法账号，并验证列表包含数字主键
        long accId = parseJson(mvc.perform(post(PATH_ACCOUNTS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountCreateDTO(emailUser, "邮箱管理员", AdminPrincipal.ADMIN,
                                TEST_ADMIN_PASSWORD, true, null, true, null, null, AdminPrincipal.MANAGER, true, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get(PATH_ACCOUNTS).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", hasItem(emailUser)));

        String bearerBeforeChange = loginBearer(emailUser, TEST_ADMIN_PASSWORD);

        // 2. 修改账号角色/范围为 DEPARTMENT_ADMIN（限定 d1）-> 旧会话因 credential_version 递增立刻 401
        mvc.perform(put(PATH_ACCOUNT_BY_ID, accId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountUpdateDTO("降权部管", AdminPrincipal.DEPARTMENT_ADMIN, true,
                                null, true, null, null, AdminPrincipal.DEPARTMENT, false, List.of(d1)))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ME).header("Authorization", bearerBeforeChange))
                .andExpect(status().isUnauthorized());

        // 3. 启停（通过 PUT 更新 enabled）、解锁、重置密码（踢掉旧会话）与超 72 字节重置 400
        String bearer2 = loginBearer(emailUser, TEST_ADMIN_PASSWORD);
        mvc.perform(put(PATH_ACCOUNT_BY_ID, accId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountUpdateDTO("降权部管", AdminPrincipal.DEPARTMENT_ADMIN, false,
                                null, true, null, null, AdminPrincipal.DEPARTMENT, false, List.of(d1)))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ME).header("Authorization", bearer2)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH_ACCOUNT_BY_ID, accId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountUpdateDTO("降权部管", AdminPrincipal.DEPARTMENT_ADMIN, true,
                                null, true, null, null, AdminPrincipal.DEPARTMENT, false, List.of(d1)))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_ACCOUNT_UNLOCK, accId).header("Authorization", superBearer))
                .andExpect(status().isOk());

        String bearer3 = loginBearer(emailUser, TEST_ADMIN_PASSWORD);
        mvc.perform(put(PATH_ACCOUNT_PASSWORD, accId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new PasswordResetDTO(RESET_PASS))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ME).header("Authorization", bearer3)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH_ACCOUNT_PASSWORD, accId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new PasswordResetDTO(INVALID_PASS_75_UTF8))))
                .andExpect(status().isBadRequest());

        // 4. 删除账号 -> 同名重建 -> 删除前签发的旧会话依然 401（数字主键与会话表隔离）
        String bearerBeforeDelete = loginBearer(emailUser, RESET_PASS);
        mvc.perform(delete(PATH_ACCOUNT_BY_ID, accId).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_ACCOUNTS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountCreateDTO(emailUser, "重建账号", AdminPrincipal.AUDITOR,
                                TEST_ADMIN_PASSWORD, true, null, true, null, null, AdminPrincipal.MANAGER, true, null))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ME).header("Authorization", bearerBeforeDelete))
                .andExpect(status().isUnauthorized());

        // 5. 自删拒绝 400；不存在 ID 404
        long selfId = parseJson(mvc.perform(get(PATH_ME).header("Authorization", superBearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(delete(PATH_ACCOUNT_BY_ID, selfId).header("Authorization", superBearer))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(PATH_ACCOUNT_BY_ID, MISSING_ACCOUNT_ID).header("Authorization", superBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));
    }

    @Test
    @DisplayName("R19/R20-02 管理员层级约束（部门管理员须限定部门、无权建/删账号 403、非法用户名/密码 400、有下级不可删 409）")
    void hierarchyInvariantsValidationAndSubtreeProtection() throws Exception {
        long d1 = createDepartment("树一部", unique("td1"));

        // 1. 非法 DTO：点开头用户名、过短用户名、过短密码、超72字节密码 -> 400
        for (AccountCreateDTO bad : List.of(
                new AccountCreateDTO(INVALID_USERNAME_DOT, "点开头", AdminPrincipal.AUDITOR, TEST_ADMIN_PASSWORD, true, null),
                new AccountCreateDTO(INVALID_USERNAME_SHORT, "过短", AdminPrincipal.AUDITOR, TEST_ADMIN_PASSWORD, true, null),
                new AccountCreateDTO(unique("sp"), "短密", AdminPrincipal.AUDITOR, INVALID_PASS_SHORT, true, null),
                new AccountCreateDTO(unique("lp"), "长密", AdminPrincipal.AUDITOR, INVALID_PASS_75_UTF8, true, null)
        )) {
            mvc.perform(post(PATH_ACCOUNTS).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }

        // 2. 部门管理员不限定部门 -> 409/400 拒绝
        mvc.perform(post(PATH_ACCOUNTS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountCreateDTO(unique("baddept"), "未限部门", AdminPrincipal.DEPARTMENT_ADMIN,
                                TEST_ADMIN_PASSWORD, true, null, true, null, null, AdminPrincipal.DEPARTMENT, false, List.of()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));

        // 3. 有下级的管理员不可直接删除 -> 409 CONFLICT
        var parent = createAdminAndLogin("par", AdminPrincipal.SUPER_ADMIN, AdminPrincipal.MANAGER, null, null);
        mvc.perform(post(PATH_ACCOUNTS).header("Authorization", parent.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountCreateDTO(unique("chd"), "下级", AdminPrincipal.DEPARTMENT_ADMIN,
                                TEST_ADMIN_PASSWORD, true, null, true, parent.id(), null, AdminPrincipal.DEPARTMENT, false, List.of(d1)))))
                .andExpect(status().isOk());
        mvc.perform(delete(PATH_ACCOUNT_BY_ID, parent.id()).header("Authorization", superBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));

        // 4. 审计员无 account.manage -> 403
        var auditor = createAuditor();
        mvc.perform(get(PATH_ACCOUNTS).header("Authorization", auditor.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
    }
}
