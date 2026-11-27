package com.melonhub.rbac.r09;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.context.dto.ContextIssueDTO;
import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.api.shared.dto.IdsDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R9 权限上下文签发（含 CloneFactory 拷贝）、激活角色子集与全局跨部门切换失效 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R09_ContextIssueControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、权限码、边界角色上限与预期错误码）
    // =========================================================================
    private static final String PATH_CONTEXTS = "/api/v1/contexts";
    private static final String PATH_EFFECTIVE = "/api/v1/contexts/effective";
    private static final String PATH_ROLE_PERMS = "/api/admin/roles/{id}/permissions";
    private static final String PATH_USER_ROLES = "/api/admin/users/{id}/roles";
    private static final String PATH_USER_STATUS = "/api/admin/users/{id}/status";
    private static final String PATH_APP_STATUS = "/api/admin/applications/{id}/status";

    private static final String PERM_READ = "doc:read";
    private static final String PERM_WRITE = "doc:write";
    private static final String MISSING_EXT_ID = "ghost-ext-id";
    private static final int OVER_MAX_ACTIVE_ROLES_1001 = 1001;

    private static final String ERR_DEPARTMENT_MISMATCH = "DEPARTMENT_MISMATCH";
    private static final String ERR_CONTEXT_INVALID = "CONTEXT_INVALID";
    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_UNAUTHORIZED = "UNAUTHORIZED";

    @Test
    @DisplayName("R9-01 全部激活/子集激活/空数组、跨部门切换递增 contextVersion 并作废旧部门所有软件上下文、switchDepartment=false 返 409")
    void issueSubsetsDepartmentSwitchInvalidationAndMismatchGuard() throws Exception {
        long d1 = createDepartment("上下文一部", unique("cd1"));
        long d2 = createDepartment("上下文二部", unique("cd2"));
        var app1 = createApplication("CtxApp1", unique("ca1"));
        var app2 = createApplication("CtxApp2", unique("ca2"));
        String ext = unique("ctx-u");
        long userId = createUser("多部门员", ext, "", List.of(d1, d2));

        long pRead = createPermission(d1, app1.id(), "读", PERM_READ, "doc", "read");
        long pWrite = createPermission(d1, app1.id(), "写", PERM_WRITE, "doc", "write");
        long r1 = createRole(d1, app1.id(), "Reader", "reader", "");
        long r2 = createRole(d1, app1.id(), "Writer", "writer", "");
        long rUnassigned = createRole(d1, app1.id(), "Admin", "admin", "");
        long rD2 = createRole(d2, app1.id(), "D2Role", "d2-role", "");
        mvc.perform(put(PATH_ROLE_PERMS, r1).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(pRead)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_ROLE_PERMS, r2).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(pWrite)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_USER_ROLES, userId).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app1.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new UserRolesDTO(List.of(r1, r2), null, null))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_USER_ROLES, userId).param("departmentId", String.valueOf(d2)).param("applicationId", String.valueOf(app1.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new UserRolesDTO(List.of(rD2), null, null))))
                .andExpect(status().isOk());

        // 1. 首次在 d1 签发（省略 activeRoleIds -> 激活全部两个角色，switchDepartment=false 首次无当前部门亦允许）
        String body1 = mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, d1, null, false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextToken").isNotEmpty())
                .andExpect(jsonPath("$.contextVersion").value(1))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(PERM_READ, PERM_WRITE)))
                .andReturn().getResponse().getContentAsString();
        String tokenD1App1 = parseJson(body1).get("contextToken").asText();

        // 同部门在 app2 也签发一个上下文（不递增版本，仍为 1）
        String bodyApp2 = mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app2.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, d1, null, false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextVersion").value(1))
                .andReturn().getResponse().getContentAsString();
        String tokenD1App2 = parseJson(bodyApp2).get("contextToken").asText();

        // 2. 边界：仅激活角色子集 [r1] 与空数组 []（零角色零权限）
        mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, d1, List.of(r1), false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.permissions[0]").value(PERM_READ));
        mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, d1, List.of(), false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isEmpty())
                .andExpect(jsonPath("$.permissions").isEmpty());

        // 3. 激活未分配角色 -> 403
        mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, d1, List.of(rUnassigned), false))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));

        // 4. 跨部门且 switchDepartment=false -> 409 DEPARTMENT_MISMATCH，旧上下文不受影响
        mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, d2, null, false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_DEPARTMENT_MISMATCH));
        mvc.perform(get(PATH_EFFECTIVE).header("X-API-Key", app1.apiKey()).header("X-Context-Token", tokenD1App1))
                .andExpect(status().isOk());

        // 5. 跨部门且 switchDepartment=true -> contextVersion 递增为 2，旧部门 app1 与 app2 全部上下文立即失效，切回不恢复
        mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, d2, null, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentId").value(d2))
                .andExpect(jsonPath("$.contextVersion").value(2));
        mvc.perform(get(PATH_EFFECTIVE).header("X-API-Key", app1.apiKey()).header("X-Context-Token", tokenD1App1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_CONTEXT_INVALID));
        mvc.perform(get(PATH_EFFECTIVE).header("X-API-Key", app2.apiKey()).header("X-Context-Token", tokenD1App2))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_CONTEXT_INVALID));
        // 切回 d1（contextVersion 递增为 3）-> 之前在 d1 的旧令牌 tokenD1App1 绝不恢复
        mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, d1, null, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextVersion").value(3));
        mvc.perform(get(PATH_EFFECTIVE).header("X-API-Key", app1.apiKey()).header("X-Context-Token", tokenD1App1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_CONTEXT_INVALID));
    }

    @Test
    @DisplayName("R9-02 非成员/停用用户/停用软件 403、不存在用户 404、非法 DTO 400 与无 API Key 401")
    void issueRejectsIneligibleStatesInvalidDtosAndMissingApiKey() throws Exception {
        long d1 = createDepartment("校验一部", unique("vd1"));
        long d2 = createDepartment("校验二部", unique("vd2"));
        var app = createApplication("ValApp", unique("va"));
        String ext = unique("vu");
        long userId = createUser("用户", ext, "", List.of(d1));

        // 1. 非成员 403、不存在用户 404
        mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, d2, null, true))))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(MISSING_EXT_ID, d1, null, true))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));

        // 2. 用户停用 403 -> 恢复后软件停用 403
        mvc.perform(patch(PATH_USER_STATUS, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(false))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, d1, null, true))))
                .andExpect(status().isForbidden());
        mvc.perform(patch(PATH_USER_STATUS, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(true))))
                .andExpect(status().isOk());
        mvc.perform(patch(PATH_APP_STATUS, app.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(false))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, d1, null, true))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_UNAUTHORIZED));
        mvc.perform(patch(PATH_APP_STATUS, app.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(true))))
                .andExpect(status().isOk());

        // 3. 非法 DTO -> 400；无 API Key -> 401
        List<Long> over1001 = LongStream.rangeClosed(1, OVER_MAX_ACTIVE_ROLES_1001).boxed().toList();
        for (ContextIssueDTO bad : List.of(
                new ContextIssueDTO(" ", d1, null, null),
                new ContextIssueDTO(ext, 0L, null, null),
                new ContextIssueDTO(ext, -1L, null, null),
                new ContextIssueDTO(ext, d1, List.of(-1L), null),
                new ContextIssueDTO(ext, d1, over1001, null)
        )) {
            mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app.apiKey())
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }
        mvc.perform(post(PATH_CONTEXTS).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, d1, null, null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_UNAUTHORIZED));
    }
}
