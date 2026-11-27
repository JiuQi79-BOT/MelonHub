package com.melonhub.rbac.r13;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.api.governance.dto.ReviewCreateDTO;
import com.melonhub.rbac.api.governance.dto.ReviewItemDecisionDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R13 定期权限复核与 grant_id 防误回收 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R13_AccessReviewControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、复核名称/期限边界 100/101 与 1/365 及预期错误码）
    // =========================================================================
    private static final String PATH_REVIEWS = "/api/admin/reviews";
    private static final String PATH_REVIEW_BY_ID = "/api/admin/reviews/{id}";
    private static final String PATH_REVIEW_ITEMS = "/api/admin/reviews/{id}/items";
    private static final String PATH_REVIEW_ITEM = "/api/admin/reviews/{id}/items/{itemId}";
    private static final String PATH_REVIEW_CLOSE = "/api/admin/reviews/{id}/close";
    private static final String PATH_USER_ROLES = "/api/admin/users/{id}/roles";
    private static final String PATH_USER_BY_ID = "/api/admin/users/{id}";
    private static final String PATH_ROLE_BY_ID = "/api/admin/roles/{id}";

    private static final String BOUNDARY_NAME_100 = "复".repeat(100);
    private static final String INVALID_NAME_101 = "复".repeat(101);
    private static final int BOUNDARY_DUE_MIN = 1;
    private static final int BOUNDARY_DUE_MAX = 365;
    private static final int INVALID_DUE_0 = 0;
    private static final int INVALID_DUE_366 = 366;

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_CONFLICT = "CONFLICT";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";

    @Test
    @DisplayName("R13-01 发起复核（边界/非法/越域403）、列表明细决策、关闭精准回收（已续期换 grant_id 跳过不误伤）与重复关闭 409")
    void reviewFullLifecycleAndGrantIdSafeRevocation() throws Exception {
        long d1 = createDepartment("复核部", unique("revd"));
        var app = createApplication("RevApp", unique("rva"));
        long uRevoke = createUser("待回收员", unique("ru1"), "", List.of(d1));
        long uRenewed = createUser("已续期员", unique("ru2"), "", List.of(d1));
        long roleId = createRole(d1, app.id(), "敏感角色", "sens", "");
        mvc.perform(put(PATH_USER_ROLES, uRevoke).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(roleId), 15, null))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_USER_ROLES, uRenewed).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(roleId), 15, null))))
                .andExpect(status().isOk());

        // 1. 发起复核（100 字名 + 1 天边界）& 全软件复核（365 天边界）
        long reviewId = parseJson(mvc.perform(post(PATH_REVIEWS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ReviewCreateDTO(BOUNDARY_NAME_100, app.id(), d1, BOUNDARY_DUE_MIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.items").value(2))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post(PATH_REVIEWS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ReviewCreateDTO("全软件年度复核", app.id(), null, BOUNDARY_DUE_MAX))))
                .andExpect(status().isOk());

        // 2. 非法 DTO 400 + 部门管理员发起全软件(departmentId=null)复核 403
        for (ReviewCreateDTO bad : List.of(
                new ReviewCreateDTO(" ", app.id(), d1, 7),
                new ReviewCreateDTO(INVALID_NAME_101, app.id(), d1, 7),
                new ReviewCreateDTO("复核", 0L, d1, 7),
                new ReviewCreateDTO("复核", app.id(), d1, INVALID_DUE_0),
                new ReviewCreateDTO("复核", app.id(), d1, INVALID_DUE_366)
        )) {
            mvc.perform(post(PATH_REVIEWS).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }
        var deptAdmin = createDeptAdmin(List.of(d1));
        mvc.perform(post(PATH_REVIEWS).header("Authorization", deptAdmin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ReviewCreateDTO("越权全量", app.id(), null, 7))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));

        // 3. 列表、详情与明细查询（含非法筛选状态/决定 400）
        mvc.perform(get(PATH_REVIEWS).param("status", "OPEN").header("Authorization", superBearer)).andExpect(status().isOk());
        mvc.perform(get(PATH_REVIEWS).param("status", "WRONG").header("Authorization", superBearer)).andExpect(status().isBadRequest());
        mvc.perform(get(PATH_REVIEW_BY_ID, reviewId).header("Authorization", superBearer)).andExpect(status().isOk());
        mvc.perform(get(PATH_REVIEW_ITEMS, reviewId).param("decision", "WRONG").header("Authorization", superBearer))
                .andExpect(status().isBadRequest());

        JsonNode items = parseJson(mvc.perform(get(PATH_REVIEW_ITEMS, reviewId).header("Authorization", superBearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("items");
        long revokeItemId = -1L, renewedItemId = -1L;
        for (JsonNode item : items) {
            if (item.get("userId").asLong() == uRevoke) revokeItemId = item.get("id").asLong();
            if (item.get("userId").asLong() == uRenewed) renewedItemId = item.get("id").asLong();
        }

        // 4. 两条均标记 REVOKE（先测非法决定 400 与审计员 403）
        mvc.perform(put(PATH_REVIEW_ITEM, reviewId, revokeItemId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ReviewItemDecisionDTO("INVALID", ""))))
                .andExpect(status().isBadRequest());
        var auditor = createAuditor();
        mvc.perform(put(PATH_REVIEW_ITEM, reviewId, revokeItemId).header("Authorization", auditor.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ReviewItemDecisionDTO("KEEP", ""))))
                .andExpect(status().isForbidden());
        mvc.perform(put(PATH_REVIEW_ITEM, reviewId, revokeItemId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ReviewItemDecisionDTO("REVOKE", "离岗"))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_REVIEW_ITEM, reviewId, renewedItemId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ReviewItemDecisionDTO("REVOKE", "待回收"))))
                .andExpect(status().isOk());

        // 5. 复核关闭前对 uRenewed 重新延期（30 天）-> 真实换新 grant_id！
        mvc.perform(put(PATH_USER_ROLES, uRenewed).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(roleId), 30, null))))
                .andExpect(status().isOk());

        // 6. 关闭复核：共 2 条标记 REVOKE，实际回收 uRevoke 1 条，uRenewed 因 grant_id 变化被跳过保留（skipped=1）
        mvc.perform(post(PATH_REVIEW_CLOSE, reviewId).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.revoked").value(2))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.summary", containsString("回收 1 条授权")));
        mvc.perform(get(PATH_USER_ROLES, uRevoke).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleIds").isEmpty());
        mvc.perform(get(PATH_USER_ROLES, uRenewed).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleIds[0]").value(roleId));

        // 7. 已关闭的复核禁止再决策或重复关闭 -> 409 CONFLICT
        mvc.perform(post(PATH_REVIEW_CLOSE, reviewId).header("Authorization", superBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));
        mvc.perform(put(PATH_REVIEW_ITEM, reviewId, revokeItemId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ReviewItemDecisionDTO("KEEP", ""))))
                .andExpect(status().isConflict());

        // 8. 删除已离职用户与角色后，复核明细历史快照仍完整可查
        mvc.perform(delete(PATH_USER_BY_ID, uRevoke).header("Authorization", superBearer)).andExpect(status().isOk());
        mvc.perform(delete(PATH_ROLE_BY_ID, roleId).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_REVIEW_ITEMS, reviewId).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }
}
