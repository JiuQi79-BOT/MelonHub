package com.melonhub.rbac.r19;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.admin.dto.AccountCreateDTO;
import com.melonhub.rbac.api.admin.dto.AccountUpdateDTO;
import com.melonhub.rbac.api.admin.dto.AdminRoleCreateDTO;
import com.melonhub.rbac.api.admin.dto.AdminRoleUpdateDTO;
import com.melonhub.rbac.api.organization.dto.DepartmentDTO;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R19 管理员树层级不变量、双轴范围约束与平台级功能守卫 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R19_AdminHierarchyControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、角色功能集合与预期错误码）
    // =========================================================================
    private static final String PATH_ACCOUNTS = "/api/admin/accounts";
    private static final String PATH_ACCOUNT_BY_ID = "/api/admin/accounts/{id}";
    private static final String PATH_ADMIN_ROLES = "/api/admin/admin-roles";
    private static final String PATH_ADMIN_ROLE_BY_CODE = "/api/admin/admin-roles/{code}";
    private static final String PATH_DEPARTMENTS = "/api/admin/departments";

    private static final List<String> MANAGER_FUNCS = List.of("org.view", "domain.view", "domain.assign", "account.manage");
    private static final List<String> MANAGER_FUNCS_WITH_DEPT_MANAGE = List.of("org.view", "department.manage", "account.manage");
    private static final List<String> SUB_FUNCS_WITH_AUDIT = List.of("org.view", "domain.view", "domain.assign", "audit.view");

    private static final String ERR_CONFLICT = "CONFLICT";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";

    @Test
    @DisplayName("R19-01 非根管理员子树隔离（越部门/越软件建下级409、改删上级或平级403、环状移交409、改角色破坏层级409）")
    void subtreeScopeIsolationCycleDefenseAndRoleMutationGuard() throws Exception {
        long d1 = createDepartment("层级一部", unique("hd1"));
        long d2 = createDepartment("层级二部", unique("hd2"));
        var app1 = createApplication("HApp1", unique("ha1"));
        var app2 = createApplication("HApp2", unique("ha2"));

        String mgrRole = ("MGR_" + tag).toUpperCase();
        String subRole = ("SUB_" + tag).toUpperCase();
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(mgrRole, "区域经理", "", MANAGER_FUNCS))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(subRole, "基层运营", "", List.of("org.view", "domain.view")))))
                .andExpect(status().isOk());

        // 建立两个平级非根经理：east(限定 d1, app1) 与 west(限定 d2, app2)
        var east = createAdminAndLogin("east", mgrRole, AdminPrincipal.MANAGER, List.of(d1), List.of(app1.id()));
        var west = createAdminAndLogin("west", mgrRole, AdminPrincipal.MANAGER, List.of(d2), List.of(app2.id()));

        // 1. east 在自身范围 (d1, app1) 内合法创建下级 -> 200
        long childId = parseJson(mvc.perform(post(PATH_ACCOUNTS).header("Authorization", east.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountCreateDTO(unique("ec"), "东区下级", subRole, TEST_ADMIN_PASSWORD,
                                true, List.of(app1.id()), false, east.id(), null, AdminPrincipal.MANAGER, false, List.of(d1)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        // 2. east 试图给下级超部门(d2)或超软件(app2)范围 -> 409 CONFLICT
        mvc.perform(post(PATH_ACCOUNTS).header("Authorization", east.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountCreateDTO(unique("overd"), "超部门", subRole, TEST_ADMIN_PASSWORD,
                                true, List.of(app1.id()), false, east.id(), null, AdminPrincipal.MANAGER, false, List.of(d1, d2)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));
        mvc.perform(post(PATH_ACCOUNTS).header("Authorization", east.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountCreateDTO(unique("overa"), "超软件", subRole, TEST_ADMIN_PASSWORD,
                                true, List.of(app1.id(), app2.id()), false, east.id(), null, AdminPrincipal.MANAGER, false, List.of(d1)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));

        // 3. east 试图操作平级 west 或挂到 west 下 -> 403 FORBIDDEN
        mvc.perform(delete(PATH_ACCOUNT_BY_ID, west.id()).header("Authorization", east.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        mvc.perform(post(PATH_ACCOUNTS).header("Authorization", east.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountCreateDTO(unique("underw"), "跨树", subRole, TEST_ADMIN_PASSWORD,
                                true, List.of(app1.id()), false, west.id(), null, AdminPrincipal.MANAGER, false, List.of(d1)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));

        // 4. 将 east 移交到其下级 childId 之下形成环路 -> 409 CONFLICT
        mvc.perform(put(PATH_ACCOUNT_BY_ID, east.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountUpdateDTO("环路移交", mgrRole, true,
                                List.of(app1.id()), false, childId, null, AdminPrincipal.MANAGER, false, List.of(d1)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));

        // 5. 修改下级角色 subRole 增加上级 east 不具备的 audit.view -> 破坏树不变量，409 CONFLICT
        mvc.perform(put(PATH_ADMIN_ROLE_BY_CODE, subRole).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleUpdateDTO("越权扩张", "", SUB_FUNCS_WITH_AUDIT))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));
    }

    @Test
    @DisplayName("R19-02 APP_ADMIN 双轴范围约束（409）、平台级功能需全部门范围（403）与最后一名启用超级管理员保护（409）")
    void builtInAxisPlatformWideFunctionAndLastSuperAdminProtection() throws Exception {
        long d1 = createDepartment("轴部", unique("axd"));
        var app = createApplication("AxisApp", unique("axa"));

        // 1. 内置 APP_ADMIN 必须限定软件且为全部部门 -> 全软件或限部门均 409 CONFLICT
        mvc.perform(post(PATH_ACCOUNTS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountCreateDTO(unique("aa-all"), "全软件非法", AdminPrincipal.APP_ADMIN,
                                TEST_ADMIN_PASSWORD, true, null, true, null, null, AdminPrincipal.MANAGER, true, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));
        mvc.perform(post(PATH_ACCOUNTS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountCreateDTO(unique("aa-dept"), "限部门非法", AdminPrincipal.APP_ADMIN,
                                TEST_ADMIN_PASSWORD, true, List.of(app.id()), false, null, null, AdminPrincipal.MANAGER, false, List.of(d1)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));

        // 2. 拥有 department.manage（platformWide=true）但部门范围被限定为 d1 的管理员调用建部门接口 -> 403 FORBIDDEN
        String platRole = ("PLATD_" + tag).toUpperCase();
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(platRole, "限部门平台角色", "", MANAGER_FUNCS_WITH_DEPT_MANAGE))))
                .andExpect(status().isOk());
        var limitedMgr = createAdminAndLogin("limplat", platRole, AdminPrincipal.MANAGER, List.of(d1), null);
        mvc.perform(post(PATH_DEPARTMENTS).header("Authorization", limitedMgr.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DepartmentDTO("被拒平台部门", unique("blk")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));

        // 3. 至少保留一名启用的 SUPER_ADMIN（非根无法改根 403；当仅剩 1 名启用超管时停用/删除该超管均 409 CONFLICT）
        Long rootSuperId = db.queryForObject(
                "SELECT id FROM admin_accounts WHERE role = 'SUPER_ADMIN' AND enabled = 1 ORDER BY id ASC LIMIT 1",
                Long.class);
        var secondSuper = createAdminAndLogin("sup2", AdminPrincipal.SUPER_ADMIN, AdminPrincipal.MANAGER, null, null);
        mvc.perform(put(PATH_ACCOUNT_BY_ID, rootSuperId).header("Authorization", secondSuper.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountUpdateDTO("越权改根", AdminPrincipal.SUPER_ADMIN, false,
                                null, true, null, null, AdminPrincipal.MANAGER, true, null))))
                .andExpect(status().isForbidden());

        String acctMgrRole = ("AMGR_" + tag).toUpperCase();
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(acctMgrRole, "账号管", "", List.of("org.view", "account.manage")))))
                .andExpect(status().isOk());
        var rootMgr = createAdminAndLogin("rootmgr", acctMgrRole, AdminPrincipal.MANAGER, null, null);
        db.update("UPDATE admin_accounts SET parent_id = NULL WHERE id = ?", rootMgr.id());
        db.update("UPDATE admin_accounts SET enabled = 0 WHERE role = 'SUPER_ADMIN' AND id <> ?", secondSuper.id());
        mvc.perform(put(PATH_ACCOUNT_BY_ID, secondSuper.id()).header("Authorization", rootMgr.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccountUpdateDTO("停用最后超管", AdminPrincipal.SUPER_ADMIN, false,
                                null, true, null, null, AdminPrincipal.MANAGER, true, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));
        mvc.perform(delete(PATH_ACCOUNT_BY_ID, secondSuper.id()).header("Authorization", rootMgr.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));
    }
}
