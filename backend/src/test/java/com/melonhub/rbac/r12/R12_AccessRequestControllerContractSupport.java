package com.melonhub.rbac.r12;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.governance.dto.AccessRequestCancelDTO;
import com.melonhub.rbac.api.governance.dto.AccessRequestSubmitDTO;
import com.melonhub.rbac.api.governance.dto.RequestApproveDTO;
import com.melonhub.rbac.api.governance.dto.RequestRejectDTO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R12 角色申请与审批流程 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R12_AccessRequestControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、边界天数 1/3650、边界理由长度 500/501 与预期错误码）
    // =========================================================================
    private static final String PATH_V1_REQUESTS = "/api/v1/access-requests";
    private static final String PATH_V1_CANCEL = "/api/v1/access-requests/{id}/cancel";
    private static final String PATH_ADMIN_REQUESTS = "/api/admin/access-requests";
    private static final String PATH_ADMIN_APPROVE = "/api/admin/access-requests/{id}/approve";
    private static final String PATH_ADMIN_REJECT = "/api/admin/access-requests/{id}/reject";
    private static final String PATH_ADMIN_ROLE_STATUS = "/api/admin/roles/{id}/status";
    private static final String PATH_ADMIN_USER_ROLES = "/api/admin/users/{id}/roles";

    private static final int BOUNDARY_MIN_DAYS = 1;
    private static final int BOUNDARY_MAX_DAYS = 3650;
    private static final int INVALID_DAYS_0 = 0;
    private static final int INVALID_DAYS_3651 = 3651;
    private static final int OTHER_APP_FILLER_REQUESTS = 55;
    private static final String BOUNDARY_REASON_500 = "理".repeat(500);
    private static final String INVALID_REASON_501 = "理".repeat(501);

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_CONFLICT = "CONFLICT";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";

    @Test
    @DisplayName("R12-01 业务端提交（边界 1/3650 天与 500 字理由）、重复待审 409、查询、本人撤回、非法 DTO 400 与非本人撤回 404")
    void businessSubmitListCancelAndValidation() throws Exception {
        long d1 = createDepartment("申请部", unique("reqd"));
        long d2 = createDepartment("他部", unique("reqd2"));
        var app = createApplication("ReqApp", unique("ra"));
        String ext1 = unique("ru1");
        String ext2 = unique("ru2");
        createUser("申请员一", ext1, "", List.of(d1));
        createUser("申请员二", ext2, "", List.of(d1));
        long r1 = createRole(d1, app.id(), "申请角色1", "req-r1", "");
        long r2 = createRole(d1, app.id(), "申请角色2", "req-r2", "");

        // 1. 正常提交（500字理由 + 1天）& 重复待审同一角色 -> 409 CONFLICT
        String req1Body = mvc.perform(post(PATH_V1_REQUESTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccessRequestSubmitDTO(ext1, d1, r1, BOUNDARY_REASON_500, BOUNDARY_MIN_DAYS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long req1Id = parseJson(req1Body).get("id").asLong();

        mvc.perform(post(PATH_V1_REQUESTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccessRequestSubmitDTO(ext1, d1, r1, "重复提交", 7))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));

        // 2. 边界 3650 天提交 r2、非成员提交 403、非法 DTO 400
        mvc.perform(post(PATH_V1_REQUESTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccessRequestSubmitDTO(ext1, d1, r2, "长期需要", BOUNDARY_MAX_DAYS))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_V1_REQUESTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccessRequestSubmitDTO(ext1, d2, r1, "跨部", 7))))
                .andExpect(status().isForbidden());
        for (AccessRequestSubmitDTO bad : List.of(
                new AccessRequestSubmitDTO(" ", d1, r1, "ok", 7),
                new AccessRequestSubmitDTO(ext1, 0L, r1, "ok", 7),
                new AccessRequestSubmitDTO(ext1, d1, 0L, "ok", 7),
                new AccessRequestSubmitDTO(ext1, d1, r1, " ", 7),
                new AccessRequestSubmitDTO(ext1, d1, r1, INVALID_REASON_501, 7),
                new AccessRequestSubmitDTO(ext1, d1, r1, "ok", INVALID_DAYS_0),
                new AccessRequestSubmitDTO(ext1, d1, r1, "ok", INVALID_DAYS_3651)
        )) {
            mvc.perform(post(PATH_V1_REQUESTS).header("X-API-Key", app.apiKey())
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }

        // 3. 本人列表、非本人撤回 404、空白 DTO 400、本人撤回 200、再次撤回 409
        mvc.perform(get(PATH_V1_REQUESTS).param("externalId", ext1).header("X-API-Key", app.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(post(PATH_V1_CANCEL, req1Id).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new AccessRequestCancelDTO(ext2))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));
        mvc.perform(post(PATH_V1_CANCEL, req1Id).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new AccessRequestCancelDTO(" "))))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH_V1_CANCEL, req1Id).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new AccessRequestCancelDTO(ext1))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_V1_CANCEL, req1Id).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new AccessRequestCancelDTO(ext1))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("R12-02 管理端查询（非法状态400）、批准（永久/互斥400/停用角色409/真实落库授权）、驳回（超长400/越权403）")
    void adminListApproveRejectAndScopes() throws Exception {
        long d1 = createDepartment("审批部", unique("aprd"));
        var app1 = createApplication("AprApp1", unique("apa1"));
        var app2 = createApplication("AprApp2", unique("apa2"));
        String ext = unique("apru");
        long userId = createUser("审批员", ext, "", List.of(d1));
        long r1 = createRole(d1, app1.id(), "审批角1", "ar1", "");
        long r2 = createRole(d1, app1.id(), "审批角2", "ar2", "");
        long rDisabled = createRole(d1, app1.id(), "停用角", "ar-dis", "");

        long reqApproveId = parseJson(mvc.perform(post(PATH_V1_REQUESTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccessRequestSubmitDTO(ext, d1, r1, "申请 r1", 7))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long reqRejectId = parseJson(mvc.perform(post(PATH_V1_REQUESTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccessRequestSubmitDTO(ext, d1, r2, "申请 r2", 7))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long reqDisabledId = parseJson(mvc.perform(post(PATH_V1_REQUESTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccessRequestSubmitDTO(ext, d1, rDisabled, "申请停用角", 7))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        // 1. 查询列表与非法状态 400
        mvc.perform(get(PATH_ADMIN_REQUESTS).param("applicationId", String.valueOf(app1.id()))
                        .param("status", "PENDING").header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get(PATH_ADMIN_REQUESTS).param("status", "UNKNOWN").header("Authorization", superBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));

        // 2. 批准互斥参数(permanent=true + durationDays) -> 400；停用角色不可批准 -> 409
        mvc.perform(post(PATH_ADMIN_APPROVE, reqApproveId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RequestApproveDTO(10, "冲突", true))))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(PATH_ADMIN_ROLE_STATUS, rDisabled).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app1.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(false))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_ADMIN_APPROVE, reqDisabledId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RequestApproveDTO(7, "审批", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));

        // 3. 批准永久授权 -> 真实写入 user_roles（无过期时间）；重复批准 -> 409
        mvc.perform(post(PATH_ADMIN_APPROVE, reqApproveId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RequestApproveDTO(null, "同意永久", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.grantExpiresAt").isEmpty());
        mvc.perform(get(PATH_ADMIN_USER_ROLES, userId).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app1.id())).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleIds[0]").value(r1));
        mvc.perform(post(PATH_ADMIN_APPROVE, reqApproveId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RequestApproveDTO(null, "重复", true))))
                .andExpect(status().isConflict());

        // 4. 驳回：超长评语 400、越权软件管理员 403、审计员 403、正常驳回 200
        mvc.perform(post(PATH_ADMIN_REJECT, reqRejectId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RequestRejectDTO(INVALID_REASON_501))))
                .andExpect(status().isBadRequest());
        var app2Admin = createAppAdmin(List.of(app2.id()));
        mvc.perform(post(PATH_ADMIN_REJECT, reqRejectId).header("Authorization", app2Admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new RequestRejectDTO("越权"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        var auditor = createAuditor();
        mvc.perform(post(PATH_ADMIN_REJECT, reqRejectId).header("Authorization", auditor.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new RequestRejectDTO("只读"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH_ADMIN_REJECT, reqRejectId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new RequestRejectDTO("不符合最小权限"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // 5. 本人申请查询先按当前调用软件过滤、再应用 50 条上限（即使其他软件存在 >50 条更新申请也不挤掉本软件申请）
        long otherRole = createRole(d1, app2.id(), "他软件角", "other-r", "");
        for (int i = 0; i < OTHER_APP_FILLER_REQUESTS; i++) {
            db.update("INSERT INTO access_requests(user_id,department_id,application_id,role_id,reason,status,requested_by,"
                            + "user_name,external_id,role_name,role_code,department_name) "
                            + "VALUES (?,?,?,?,?,'REJECTED','test','审批员',?,'他软件角','other-r','审批部')",
                    userId, d1, app2.id(), otherRole, "filler-" + i, ext);
        }
        mvc.perform(get(PATH_V1_REQUESTS).param("externalId", ext).header("X-API-Key", app1.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].applicationId").value(app1.id()));
    }
}
