package com.melonhub.rbac.r05;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.domainrbac.dto.PermissionDTO;
import com.melonhub.rbac.api.domainrbac.dto.RoleDTO;
import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.api.shared.dto.IdsDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

/**
 * R5 隔离域（部门 × 软件）逻辑隔离、双轴管理员越域拒绝、同名同编码共存与 V15/V16 复合外键实库约束 —— 真实 MySQL Controller 层双策略测试。
 */
abstract class R05_DomainIsolationControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、不存在域 ID、共享编码与预期错误码）
    // =========================================================================
    private static final String PATH_ADMIN_ROLES = "/api/admin/roles";
    private static final String PATH_ADMIN_ROLE_BY_ID = "/api/admin/roles/{id}";
    private static final String PATH_ADMIN_ROLE_STATUS = "/api/admin/roles/{id}/status";
    private static final String PATH_ADMIN_ROLE_PERMS = "/api/admin/roles/{id}/permissions";
    private static final String PATH_ADMIN_ROLE_PARENTS = "/api/admin/roles/{id}/parents";
    private static final String PATH_ADMIN_PERM_BY_ID = "/api/admin/permissions/{id}";
    private static final String PATH_ADMIN_USER_ROLES = "/api/admin/users/{id}/roles";
    private static final String PATH_V1_ROLE_PERMS = "/api/v1/departments/{dept}/roles/{id}/permissions";

    private static final long MISSING_DEPT_ID = 9_999_999L;
    private static final long MISSING_APP_ID = 8_888_888L;
    private static final String SHARED_ROLE_CODE = "domain-reader";
    private static final String SHARED_PERM_CODE = "item:read";

    private static final String ERR_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";

    @Test
    @DisplayName("R5-01 不存在域 404、双轴管理员越域 403、异域角色/权限读写关联与分配全量拒绝 403、跨隔离域同编码互不冲突")
    void domainIsolationRejectsMissingOutOfScopeAndCrossDomainOperations() throws Exception {
        long d1 = createDepartment("隔离一部", unique("iso1"));
        long d2 = createDepartment("隔离二部", unique("iso2"));
        var app1 = createApplication("IsoApp1", unique("ia1"));
        var app2 = createApplication("IsoApp2", unique("ia2"));
        long userId = createUser("多部门用户", unique("iu"), "", List.of(d1, d2));

        // 1. 跨部门、跨软件创建完全相同的角色编码与权限编码互不冲突
        long rD1A1 = createRole(d1, app1.id(), "Reader", SHARED_ROLE_CODE, "");
        long rD2A1 = createRole(d2, app1.id(), "Reader", SHARED_ROLE_CODE, "");
        long rD1A2 = createRole(d1, app2.id(), "Reader", SHARED_ROLE_CODE, "");
        long pD2A1 = createPermission(d2, app1.id(), "Read", SHARED_PERM_CODE, "item", "read");
        long pD1A2 = createPermission(d1, app2.id(), "Read", SHARED_PERM_CODE, "item", "read");

        // 2. 不存在的部门 / 软件 -> 404
        mvc.perform(get(PATH_ADMIN_ROLES).param("departmentId", String.valueOf(MISSING_DEPT_ID))
                        .param("applicationId", String.valueOf(app1.id())).header("Authorization", superBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));
        mvc.perform(get(PATH_ADMIN_ROLES).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(MISSING_APP_ID)).header("Authorization", superBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));

        // 3. 双轴管理员范围隔离：软件管理员跨软件 403，部门管理员跨部门 403
        var app1Admin = createAppAdmin(List.of(app1.id()));
        var dept1Admin = createDeptAdmin(List.of(d1));
        mvc.perform(get(PATH_ADMIN_ROLES).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app2.id())).header("Authorization", app1Admin.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        mvc.perform(get(PATH_ADMIN_ROLES).param("departmentId", String.valueOf(d2))
                        .param("applicationId", String.valueOf(app1.id())).header("Authorization", dept1Admin.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));

        // 4. 在 (d1, app1) 域操作 (d2, app1) 或 (d1, app2) 的角色/权限 -> 全部 403
        for (long foreignRole : List.of(rD2A1, rD1A2)) {
            mvc.perform(put(PATH_ADMIN_ROLE_BY_ID, foreignRole)
                            .param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                            .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                            .content(json(new RoleDTO("X", "x", ""))))
                    .andExpect(status().isForbidden());
            mvc.perform(patch(PATH_ADMIN_ROLE_STATUS, foreignRole)
                            .param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                            .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                            .content(json(new EnabledDTO(false))))
                    .andExpect(status().isForbidden());
            mvc.perform(delete(PATH_ADMIN_ROLE_BY_ID, foreignRole)
                            .param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                            .header("Authorization", superBearer))
                    .andExpect(status().isForbidden());
            mvc.perform(put(PATH_ADMIN_ROLE_PARENTS, rD1A1)
                            .param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                            .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                            .content(json(new IdsDTO(List.of(foreignRole)))))
                    .andExpect(status().isForbidden());
            mvc.perform(put(PATH_ADMIN_USER_ROLES, userId)
                            .param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                            .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UserRolesDTO(List.of(foreignRole), null, null))))
                    .andExpect(status().isForbidden());
        }
        for (long foreignPerm : List.of(pD2A1, pD1A2)) {
            mvc.perform(put(PATH_ADMIN_PERM_BY_ID, foreignPerm)
                            .param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                            .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                            .content(json(new PermissionDTO("P", "p:x", "p", "x"))))
                    .andExpect(status().isForbidden());
            mvc.perform(delete(PATH_ADMIN_PERM_BY_ID, foreignPerm)
                            .param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                            .header("Authorization", superBearer))
                    .andExpect(status().isForbidden());
            mvc.perform(put(PATH_ADMIN_ROLE_PERMS, rD1A1)
                            .param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                            .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                            .content(json(new IdsDTO(List.of(foreignPerm)))))
                    .andExpect(status().isForbidden());
            mvc.perform(put(PATH_V1_ROLE_PERMS, d1, rD1A1).header("X-API-Key", app1.apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new IdsDTO(List.of(foreignPerm)))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("R5-02 真实 MySQL V15/V16 物理复合外键兜底拒绝跨域写入 role_permissions 与 user_roles")
    void databaseCompositeForeignKeysRejectCrossDomainInsert() throws Exception {
        long d1 = createDepartment("外键一部", unique("fk1"));
        long d2 = createDepartment("外键二部", unique("fk2"));
        var app1 = createApplication("FkApp", unique("fka"));
        long roleD1 = createRole(d1, app1.id(), "R1", "r1", "");
        long permD2 = createPermission(d2, app1.id(), "P2", "p:2", "p", "2");
        long userId = createUser("外键员", unique("fku"), "", List.of(d1, d2));

        assertThatThrownBy(() -> db.update(
                "INSERT INTO role_permissions(role_id, permission_id, department_id, application_id) VALUES (?, ?, ?, ?)",
                roleD1, permD2, d1, app1.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update(
                "INSERT INTO user_roles(user_id, role_id, department_id, application_id, grant_id) VALUES (?, ?, ?, ?, ?)",
                userId, roleD1, d2, app1.id(), unique("gid")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
