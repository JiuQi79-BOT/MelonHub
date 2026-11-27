package com.melonhub.rbac.r11;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.context.dto.ContextIssueDTO;
import com.melonhub.rbac.api.domainrbac.dto.SimulationDTO;
import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.api.shared.dto.IdsDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R11 权限诊断模拟、权限持有者反查、即将到期报表、全局搜索、域概览与业务端权限自查 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R11_SimulateAndLookupControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、权限编码、到期报表边界天数与预期错误码）
    // =========================================================================
    private static final String PATH_SIMULATE = "/api/admin/simulate";
    private static final String PATH_PERM_WHO = "/api/admin/permissions/who";
    private static final String PATH_EXPIRING = "/api/admin/assignments/expiring";
    private static final String PATH_SEARCH = "/api/admin/search";
    private static final String PATH_OVERVIEW = "/api/admin/overview";
    private static final String PATH_V1_SELF_ACCESS = "/api/v1/users/access";
    private static final String PATH_V1_CONTEXTS = "/api/v1/contexts";
    private static final String PATH_ROLE_PERMS = "/api/admin/roles/{id}/permissions";
    private static final String PATH_USER_ROLES = "/api/admin/users/{id}/roles";

    private static final String PERM_READ = "doc:read";
    private static final String PERM_DELETE = "doc:delete";
    private static final String DAYS_BOUNDARY_MIN = "1";
    private static final String DAYS_BOUNDARY_MAX = "365";
    private static final String DAYS_INVALID_0 = "0";
    private static final String DAYS_INVALID_366 = "366";

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";

    @Test
    @DisplayName("R11-01 权限诊断模拟：过期角色写 notices 不阻塞、跨部门提示 switchesDepartment 且不修改当前部门、非法 DTO 400 与越域 403")
    void simulateCoversNoticesCrossDepartmentNonMutationValidationAndScope() throws Exception {
        long d1 = createDepartment("诊断一部", unique("sd1"));
        long d2 = createDepartment("诊断二部", unique("sd2"));
        var app1 = createApplication("SimApp1", unique("sa1"));
        var app2 = createApplication("SimApp2", unique("sa2"));
        String ext = unique("sim-u");
        long userId = createUser("诊断员", ext, "sim@example.com", List.of(d1, d2));
        long pRead = createPermission(d1, app1.id(), "读文档", PERM_READ, "doc", "read");
        long rActive = createRole(d1, app1.id(), "有效角色", "active-r", "");
        long rExpired = createRole(d1, app1.id(), "过期角色", "expired-r", "");
        mvc.perform(put(PATH_ROLE_PERMS, rActive).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(pRead)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_USER_ROLES, userId).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(rActive, rExpired), 7, null))))
                .andExpect(status().isOk());
        // 将 rExpired 真实置为已过期，并让用户当前处于 d2
        db.update("UPDATE user_roles SET expires_at = ? WHERE user_id = ? AND role_id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), userId, rExpired);
        mvc.perform(post(PATH_V1_CONTEXTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ContextIssueDTO(ext, d2, null, true))))
                .andExpect(status().isOk());

        // 1. 正常模拟 d1：eligible=true, switchesDepartment=true, notices 含 expired-r，且不修改用户的 active_department_id
        mvc.perform(post(PATH_SIMULATE).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SimulationDTO(userId, d1, app1.id(), null, List.of(PERM_READ, PERM_DELETE)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.switchesDepartment").value(true))
                .andExpect(jsonPath("$.notices", hasItem(containsString("expired-r"))))
                .andExpect(jsonPath("$.decisions['doc:read']").value(true))
                .andExpect(jsonPath("$.decisions['doc:delete']").value(false))
                .andExpect(jsonPath("$.allowed").value(false));
        Long activeDeptAfter = db.queryForObject("SELECT active_department_id FROM users WHERE id = ?", Long.class, userId);
        org.assertj.core.api.Assertions.assertThat(activeDeptAfter).isEqualTo(d2);

        // 2. 非法 DTO 400；软件管理员跨软件模拟 403
        mvc.perform(post(PATH_SIMULATE).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SimulationDTO(0L, d1, app1.id(), null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        var app1Admin = createAppAdmin(List.of(app1.id()));
        mvc.perform(post(PATH_SIMULATE).header("Authorization", app1Admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SimulationDTO(userId, d1, app2.id(), null, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
    }

    @Test
    @DisplayName("R11-02 权限持有者反查、即将到期报表（边界 1/365、非法 0/366）、全局搜索、域概览与业务端自查")
    void holdersExpiringSearchOverviewAndSelfAccess() throws Exception {
        long d1 = createDepartment("反查部", unique("whd"));
        long d2 = createDepartment("外部门", unique("whd2"));
        var app1 = createApplication("WhoApp1", unique("wa1"));
        var app2 = createApplication("WhoApp2", unique("wa2"));
        String ext = unique("whou");
        long userId = createUser("反查员工", ext, "", List.of(d1));
        long p1 = createPermission(d1, app1.id(), "读文档", PERM_READ, "doc", "read");
        long r1 = createRole(d1, app1.id(), "读角色", "reader", "");
        mvc.perform(put(PATH_ROLE_PERMS, r1).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(p1)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_USER_ROLES, userId).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(r1), 3, null))))
                .andExpect(status().isOk());

        // 1. 权限持有者反查：命中 + 缺 code 400 + 软件管理员越权 403
        mvc.perform(get(PATH_PERM_WHO).param("applicationId", String.valueOf(app1.id())).param("code", PERM_READ)
                        .header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.holders[0].externalId").value(ext));
        mvc.perform(get(PATH_PERM_WHO).param("applicationId", String.valueOf(app1.id())).header("Authorization", superBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        var app1Admin = createAppAdmin(List.of(app1.id()));
        mvc.perform(get(PATH_PERM_WHO).param("applicationId", String.valueOf(app2.id())).param("code", PERM_READ)
                        .header("Authorization", app1Admin.bearer()))
                .andExpect(status().isForbidden());

        // 2. 即将到期报表：边界 1/365 正常（7 天内应含此 3 天到期授权），非法 0/366 返 400，越权软件 403
        mvc.perform(get(PATH_EXPIRING).param("days", DAYS_BOUNDARY_MIN).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_EXPIRING).param("days", DAYS_BOUNDARY_MAX).param("applicationId", String.valueOf(app1.id()))
                        .header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].externalId", hasItem(ext)));
        mvc.perform(get(PATH_EXPIRING).param("days", DAYS_INVALID_0).header("Authorization", superBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        mvc.perform(get(PATH_EXPIRING).param("days", DAYS_INVALID_366).header("Authorization", superBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        mvc.perform(get(PATH_EXPIRING).param("days", "7").param("applicationId", String.valueOf(app2.id()))
                        .header("Authorization", app1Admin.bearer()))
                .andExpect(status().isForbidden());

        // 3. 全局搜索：命中用户 + 空白查询返回空四栏
        mvc.perform(get(PATH_SEARCH).param("q", ext).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].externalId").value(ext));
        mvc.perform(get(PATH_SEARCH).param("q", "   ").header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isEmpty())
                .andExpect(jsonPath("$.roles").isEmpty());

        // 4. 域概览 + 业务端自查（正常、非成员403、不存在用户404）
        mvc.perform(get(PATH_OVERVIEW).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app1.id())).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(1))
                .andExpect(jsonPath("$.permissions").value(1));
        mvc.perform(get(PATH_V1_SELF_ACCESS).param("externalId", ext).param("departmentId", String.valueOf(d1))
                        .header("X-API-Key", app1.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0]").value(PERM_READ));
        mvc.perform(get(PATH_V1_SELF_ACCESS).param("externalId", ext).param("departmentId", String.valueOf(d2))
                        .header("X-API-Key", app1.apiKey()))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH_V1_SELF_ACCESS).param("externalId", "missing-u").param("departmentId", String.valueOf(d1))
                        .header("X-API-Key", app1.apiKey()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));
    }
}
