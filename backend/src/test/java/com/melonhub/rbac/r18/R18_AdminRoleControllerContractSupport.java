package com.melonhub.rbac.r18;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.admin.dto.AdminRoleCreateDTO;
import com.melonhub.rbac.api.admin.dto.AdminRoleUpdateDTO;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R18 控制台 19 项功能权限守卫与自定义管理员角色 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R18_AdminRoleControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、内置角色码、非法/未知功能、预期功能数量 19 与错误码）
    // =========================================================================
    private static final String PATH_FUNCTIONS = "/api/admin/admin-functions";
    private static final String PATH_ADMIN_ROLES = "/api/admin/admin-roles";
    private static final String PATH_ADMIN_ROLE_BY_CODE = "/api/admin/admin-roles/{code}";
    private static final String PATH_ADMIN_ACCOUNT_BY_ID = "/api/admin/accounts/{id}";
    private static final String PATH_ADMIN_AUDIT = "/api/admin/audit";

    private static final int EXPECTED_FUNCTION_COUNT = 19;
    private static final String BUILT_IN_SUPER = "SUPER_ADMIN";
    private static final String BUILT_IN_AUDITOR = "AUDITOR";
    private static final String MISSING_ROLE_CODE = "NO_SUCH_ROLE";
    private static final String INVALID_CODE_LOWER = "bad-lower";
    private static final String UNKNOWN_FUNCTION = "unknown.fake.func";

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_CONFLICT = "CONFLICT";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";

    @Test
    @DisplayName("R18-01 19项功能清单、自定义角色增/查/改（即时生效于绑定管理员）/删、内置角色不可改删400、绑定占用409与提权403")
    void customAdminRoleLifecycleLiveEffectBuiltInProtectionAndAntiEscalation() throws Exception {
        String customCode = ("OPS_" + tag).toUpperCase();

        // 1. 查询 19 项功能清单与角色列表
        mvc.perform(get(PATH_FUNCTIONS).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(EXPECTED_FUNCTION_COUNT));
        mvc.perform(get(PATH_ADMIN_ROLES).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem(BUILT_IN_SUPER)));

        // 2. 新建自定义角色（初始仅有 org.view），绑定账号后验证无 audit.view -> 403
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(customCode, "运维只读", "desc", List.of("org.view")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(customCode));
        var holder = createAdminAndLogin("ops", customCode, AdminPrincipal.MANAGER, null, null);
        mvc.perform(get(PATH_ADMIN_AUDIT).header("Authorization", holder.bearer()))
                .andExpect(status().isForbidden());

        // 3. 修改自定义角色增加 audit.view -> 无需重登，绑定管理员下一次请求立即 200！
        mvc.perform(put(PATH_ADMIN_ROLE_BY_CODE, customCode).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleUpdateDTO("运维审计", "upd", List.of("org.view", "audit.view")))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ADMIN_AUDIT).header("Authorization", holder.bearer()))
                .andExpect(status().isOk());

        // 4. 禁止修改/删除内置角色 -> 400；有账号绑定的自定义角色不可删 -> 409
        mvc.perform(put(PATH_ADMIN_ROLE_BY_CODE, BUILT_IN_AUDITOR).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleUpdateDTO("篡改内置", "", List.of("org.view")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        mvc.perform(delete(PATH_ADMIN_ROLE_BY_CODE, BUILT_IN_SUPER).header("Authorization", superBearer))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(PATH_ADMIN_ROLE_BY_CODE, customCode).header("Authorization", superBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));

        // 5. 非法 DTO（小写非法码、空名、未知功能）-> 400
        for (AdminRoleCreateDTO bad : List.of(
                new AdminRoleCreateDTO(INVALID_CODE_LOWER, "坏码", "", List.of("org.view")),
                new AdminRoleCreateDTO(("BLANK_" + tag).toUpperCase(), " ", "", List.of("org.view")),
                new AdminRoleCreateDTO(("UNK_" + tag).toUpperCase(), "未知", "", List.of(UNKNOWN_FUNCTION))
        )) {
            mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }

        // 6. 反提权：仅拥有 role.manage + org.view 的管理员不能授予自己没有的 audit.view，且平台专属功能 user.manage 不可放进自定义角色 -> 403
        String subRoleMgrCode = ("RMGR_" + tag).toUpperCase();
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(subRoleMgrCode, "角色管理", "", List.of("org.view", "role.manage")))))
                .andExpect(status().isOk());
        var subRoleMgr = createAdminAndLogin("rmgr", subRoleMgrCode, AdminPrincipal.MANAGER, null, null);
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", subRoleMgr.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(("ESC_" + tag).toUpperCase(), "提权", "", List.of("org.view", "audit.view")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(("PLAT_" + tag).toUpperCase(), "平台专属", "", List.of("user.manage")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));

        // 7. 缺少 role.manage 不可建角色 -> 403；删除绑定账号后删除自定义角色成功 200，再删 404
        var auditor = createAuditor();
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", auditor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(("AUD_" + tag).toUpperCase(), "无权", "", List.of("org.view")))))
                .andExpect(status().isForbidden());
        mvc.perform(delete(PATH_ADMIN_ACCOUNT_BY_ID, holder.id()).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(delete(PATH_ADMIN_ROLE_BY_CODE, customCode).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(delete(PATH_ADMIN_ROLE_BY_CODE, MISSING_ROLE_CODE).header("Authorization", superBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));
    }

    @Test
    @DisplayName("R18-02 重复自定义管理员角色编码返回 409 CONFLICT")
    void duplicateAdminRoleCodeReturns409() throws Exception {
        String code = ("DUP_" + tag).toUpperCase();
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(code, "一", "", List.of("org.view")))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(code, "二", "", List.of("org.view")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));
    }
}
