package com.melonhub.rbac.r07;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.context.dto.ContextIssueDTO;
import com.melonhub.rbac.api.domainrbac.dto.BatchUserRolesDTO;
import com.melonhub.rbac.api.domainrbac.dto.RoleExpiryDTO;
import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R7 用户角色分配、三层到期优先级与跨页批量追加 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R07_AssignmentControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、三层到期时间、批授边界 1/3650/1000 与预期错误码）
    // =========================================================================
    private static final String PATH_USER_ROLES = "/api/admin/users/{id}/roles";
    private static final String PATH_V1_USER_ROLES = "/api/v1/departments/{dept}/users/{id}/roles";
    private static final String PATH_ASSIGNMENTS = "/api/admin/assignments";
    private static final String PATH_BATCH = "/api/admin/assignments/batch";
    private static final String PATH_V1_CONTEXTS = "/api/v1/contexts";
    private static final String PATH_V1_EFFECTIVE = "/api/v1/contexts/effective";

    private static final long MISSING_USER_ID = 9_999_999L;
    private static final int BOUNDARY_MIN_DAYS = 1;
    private static final int BOUNDARY_MAX_DAYS = 3650;
    private static final int INVALID_DAYS_0 = 0;
    private static final int INVALID_DAYS_3651 = 3651;
    private static final int OVER_MAX_BATCH_1001 = 1001;

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";

    private String grantId(long userId, long roleId) {
        return db.queryForObject("SELECT grant_id FROM user_roles WHERE user_id=? AND role_id=?", String.class, userId, roleId);
    }

    @Test
    @DisplayName("R7-01 整体替换用户角色三层到期优先级（逐角色>统一天数>保留原期与 grant_id）、查询/分页与全量非法 DTO")
    void replaceUserRolesThreeTierExpiryAndValidation() throws Exception {
        long d1 = createDepartment("授权部", unique("asg"));
        long d2 = createDepartment("外部门", unique("out"));
        var app = createApplication("AsgApp", unique("aa"));
        long u1 = createUser("授权员", unique("au1"), "", List.of(d1));
        long outsider = createUser("非成员", unique("au2"), "", List.of(d2));
        long r1 = createRole(d1, app.id(), "角色1", "r1", "");
        long r2 = createRole(d1, app.id(), "角色2", "r2", "");
        long r3 = createRole(d1, app.id(), "角色3", "r3", "");

        // 1. 首次统一授予 r1, r2（10天有效），记录 r1 与 r2 初始 grant_id
        mvc.perform(put(PATH_USER_ROLES, u1).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(r1, r2), 10, null))))
                .andExpect(status().isOk());
        String r1GrantBefore = grantId(u1, r1);
        String r2GrantBefore = grantId(u1, r2);

        // 2. 三层到期优先级：r1 未指定到期 -> 保留原期限且不换 grant_id；r2 显式设 null(永久) -> 换新 grant_id 且无到期；r3 显式设 25 天后
        OffsetDateTime explicitFuture = OffsetDateTime.now(ZoneOffset.UTC).plusDays(25).truncatedTo(ChronoUnit.SECONDS);
        mvc.perform(put(PATH_USER_ROLES, u1).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(
                                List.of(r1, r2, r3),
                                null,
                                List.of(new RoleExpiryDTO(r2, null), new RoleExpiryDTO(r3, explicitFuture))))))
                .andExpect(status().isOk());
        assertThat(grantId(u1, r1)).isEqualTo(r1GrantBefore);
        assertThat(grantId(u1, r2)).isNotEqualTo(r2GrantBefore);

        // 3. 查询（管理端 + 业务端）与域授权分页（含边界 page=0/size=500 钳制）
        mvc.perform(get(PATH_USER_ROLES, u1).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleIds.length()").value(3))
                .andExpect(jsonPath("$.roleExpiries." + r1).isNotEmpty())
                .andExpect(jsonPath("$.roleExpiries." + r2).doesNotExist())
                .andExpect(jsonPath("$.roleExpiries." + r3).isNotEmpty());
        mvc.perform(get(PATH_V1_USER_ROLES, d1, u1).header("X-API-Key", app.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleIds.length()").value(3));
        // 3.5 业务端替换用户角色（正常 200 + 异域角色 403）
        long foreignRole = createRole(d2, app.id(), "外域角色", "fr", "");
        mvc.perform(put(PATH_V1_USER_ROLES, d1, u1).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(r1, r2), null, null))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_USER_ROLES, d1, u1).header("X-API-Key", app.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleIds.length()").value(2));
        mvc.perform(put(PATH_V1_USER_ROLES, d1, u1).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(foreignRole), null, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        mvc.perform(get(PATH_ASSIGNMENTS).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).param("page", "0").param("size", "500")
                        .header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(200));

        // 4. 非法 DTO：null角色、days=0/3651、expiries 指向未分配角色、过去时间、超3650天 -> 400
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime over3650 = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3655).truncatedTo(ChronoUnit.SECONDS);
        for (UserRolesDTO bad : List.of(
                new UserRolesDTO(null, null, null),
                new UserRolesDTO(List.of(r1), INVALID_DAYS_0, null),
                new UserRolesDTO(List.of(r1), INVALID_DAYS_3651, null),
                new UserRolesDTO(List.of(r1), null, List.of(new RoleExpiryDTO(r2, explicitFuture))),
                new UserRolesDTO(List.of(r1), null, List.of(new RoleExpiryDTO(r1, past))),
                new UserRolesDTO(List.of(r1), null, List.of(new RoleExpiryDTO(r1, over3650)))
        )) {
            mvc.perform(put(PATH_USER_ROLES, u1).param("departmentId", String.valueOf(d1))
                            .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }

        // 5. 不存在用户 404、非本部门成员 403、审计员 403
        mvc.perform(put(PATH_USER_ROLES, MISSING_USER_ID).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new UserRolesDTO(List.of(), null, null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));
        mvc.perform(put(PATH_USER_ROLES, outsider).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new UserRolesDTO(List.of(r1), null, null))))
                .andExpect(status().isForbidden());
        var auditor = createAuditor();
        mvc.perform(put(PATH_USER_ROLES, u1).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", auditor.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new UserRolesDTO(List.of(), null, null))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R7-02 批量追加角色：保留已有有效分配与期限、精准统计变更数、边界天数(1/3650)、超1000/空数组400与越域403")
    void batchAppendRolesPreservesExistingAndEnforcesBounds() throws Exception {
        long d1 = createDepartment("批授部", unique("bat"));
        var app1 = createApplication("BatApp1", unique("ba1"));
        var app2 = createApplication("BatApp2", unique("ba2"));
        String ext1 = unique("bu1");
        long u1 = createUser("用户甲", ext1, "", List.of(d1));
        long u2 = createUser("用户乙", unique("bu2"), "", List.of(d1));
        long r1 = createRole(d1, app1.id(), "批授角色1", "br1", "");
        long r2 = createRole(d1, app1.id(), "批授角色2", "br2", "");

        // 先给 u1 授 r1 永久有效并在 app2 签发上下文，验证 app1 角色变更不影响 app2 上下文
        mvc.perform(put(PATH_USER_ROLES, u1).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app1.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new UserRolesDTO(List.of(r1), null, null))))
                .andExpect(status().isOk());
        String app2Token = parseJson(mvc.perform(post(PATH_V1_CONTEXTS).header("X-API-Key", app2.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ContextIssueDTO(ext1, d1, null, true))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("contextToken").asText();
        String u1R1Grant = grantId(u1, r1);

        // 1. 批量给 [u1, u2] 追加 [r1]（1天有效）：u1 已有有效 r1 被保留跳过，仅 u2 新增 1 条
        mvc.perform(post(PATH_BATCH).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app1.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BatchUserRolesDTO(List.of(u1, u2), List.of(r1), BOUNDARY_MIN_DAYS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").value(2))
                .andExpect(jsonPath("$.changedUsers").value(1))
                .andExpect(jsonPath("$.grantsAdded").value(1));
        assertThat(grantId(u1, r1)).isEqualTo(u1R1Grant);

        // 2. 边界最大天数 3650 追加 r2 给两人
        mvc.perform(post(PATH_BATCH).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app1.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BatchUserRolesDTO(List.of(u1, u2), List.of(r2), BOUNDARY_MAX_DAYS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changedUsers").value(2))
                .andExpect(jsonPath("$.grantsAdded").value(2));
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app2.apiKey()).header("X-Context-Token", app2Token))
                .andExpect(status().isOk());

        // 3. 非法 DTO：空用户/空角色/超1000用户/超1000角色/days=0或3651 -> 400
        List<Long> over1001 = LongStream.rangeClosed(1, OVER_MAX_BATCH_1001).boxed().toList();
        for (BatchUserRolesDTO bad : List.of(
                new BatchUserRolesDTO(List.of(), List.of(r1), 7),
                new BatchUserRolesDTO(List.of(u1), List.of(), 7),
                new BatchUserRolesDTO(over1001, List.of(r1), 7),
                new BatchUserRolesDTO(List.of(u1), over1001, 7),
                new BatchUserRolesDTO(List.of(u1), List.of(r1), INVALID_DAYS_0),
                new BatchUserRolesDTO(List.of(u1), List.of(r1), INVALID_DAYS_3651)
        )) {
            mvc.perform(post(PATH_BATCH).param("departmentId", String.valueOf(d1))
                            .param("applicationId", String.valueOf(app1.id())).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }

        // 4. 软件管理员跨软件批授 -> 403
        var app1Admin = createAppAdmin(List.of(app1.id()));
        mvc.perform(post(PATH_BATCH).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app2.id())).header("Authorization", app1Admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BatchUserRolesDTO(List.of(u1), List.of(r1), null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
    }
}
