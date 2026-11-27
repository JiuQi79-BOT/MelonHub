package com.melonhub.rbac.r10;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.context.dto.ContextIssueDTO;
import com.melonhub.rbac.api.context.dto.PermissionCheckDTO;
import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.api.shared.dto.IdsDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R10 在线有效权限查询、批量校验（CloneFactory CheckVO、大小写敏感、默认拒绝）与主动撤销 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R10_ContextCheckControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、权限编码大小写样本、100/101 校验上限边界与预期错误码）
    // =========================================================================
    private static final String PATH_CONTEXTS = "/api/v1/contexts";
    private static final String PATH_EFFECTIVE = "/api/v1/contexts/effective";
    private static final String PATH_CHECK = "/api/v1/contexts/check";
    private static final String PATH_ROLE_PERMS = "/api/admin/roles/{id}/permissions";
    private static final String PATH_USER_ROLES = "/api/admin/users/{id}/roles";

    private static final String PERM_READ = "item:Read";
    private static final String PERM_READ_WRONG_CASE = "item:read";
    private static final String PERM_WRITE = "item:Write";
    private static final String PERM_UNKNOWN = "item:Delete";
    private static final int BOUNDARY_MAX_CHECK_100 = 100;
    private static final int INVALID_OVER_CHECK_101 = 101;

    private static final String ERR_CONTEXT_INVALID = "CONTEXT_INVALID";
    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";

    @Test
    @DisplayName("R10-01 有效权限查询（不输出 contextToken）、批量校验（全通过/大小写敏感/默认拒绝/100上限/101非法）、跨软件401与主动撤销")
    void effectiveCheckCrossAppIsolationAndRevoke() throws Exception {
        long dept = createDepartment("校验部", unique("chk"));
        var app1 = createApplication("ChkApp1", unique("cka1"));
        var app2 = createApplication("ChkApp2", unique("cka2"));
        String ext = unique("cku");
        long userId = createUser("校验员", ext, "", List.of(dept));
        long p1 = createPermission(dept, app1.id(), "读", PERM_READ, "item", "read");
        long p2 = createPermission(dept, app1.id(), "写", PERM_WRITE, "item", "write");
        long r1 = createRole(dept, app1.id(), "编辑", "editor", "");
        mvc.perform(put(PATH_ROLE_PERMS, r1).param("departmentId", String.valueOf(dept)).param("applicationId", String.valueOf(app1.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(p1, p2)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_USER_ROLES, userId).param("departmentId", String.valueOf(dept)).param("applicationId", String.valueOf(app1.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new UserRolesDTO(List.of(r1), null, null))))
                .andExpect(status().isOk());

        String ctxBody = mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ContextIssueDTO(ext, dept, null, true))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = parseJson(ctxBody).get("contextToken").asText();

        // 1. /effective 不输出 contextToken
        mvc.perform(get(PATH_EFFECTIVE).header("X-API-Key", app1.apiKey()).header("X-Context-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextToken").doesNotExist())
                .andExpect(jsonPath("$.contextVersion").value(1));

        // 2. /check 全通过、大小写敏感默认拒绝、边界 100 项通过
        mvc.perform(post(PATH_CHECK).header("X-API-Key", app1.apiKey()).header("X-Context-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PermissionCheckDTO(List.of(PERM_READ, PERM_WRITE)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.decisions['item:Read']").value(true));

        mvc.perform(post(PATH_CHECK).header("X-API-Key", app1.apiKey()).header("X-Context-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PermissionCheckDTO(List.of(PERM_READ, PERM_READ_WRONG_CASE, PERM_UNKNOWN)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.decisions['item:Read']").value(true))
                .andExpect(jsonPath("$.decisions['item:read']").value(false))
                .andExpect(jsonPath("$.decisions['item:Delete']").value(false));

        List<String> max100 = IntStream.range(0, BOUNDARY_MAX_CHECK_100).mapToObj(i -> PERM_READ).toList();
        mvc.perform(post(PATH_CHECK).header("X-API-Key", app1.apiKey()).header("X-Context-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new PermissionCheckDTO(max100))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));

        // 3. 非法 /check DTO（null/空/空白编码/101项）-> 400
        List<String> over101 = IntStream.range(0, INVALID_OVER_CHECK_101).mapToObj(i -> "p:" + i).toList();
        for (PermissionCheckDTO bad : List.of(
                new PermissionCheckDTO(null),
                new PermissionCheckDTO(List.of()),
                new PermissionCheckDTO(List.of(" ")),
                new PermissionCheckDTO(over101)
        )) {
            mvc.perform(post(PATH_CHECK).header("X-API-Key", app1.apiKey()).header("X-Context-Token", token)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }

        // 4. 跨软件使用上下文令牌 -> 401 CONTEXT_INVALID；缺头 400；空白/伪造令牌 401
        mvc.perform(get(PATH_EFFECTIVE).header("X-API-Key", app2.apiKey()).header("X-Context-Token", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_CONTEXT_INVALID));
        mvc.perform(get(PATH_EFFECTIVE).header("X-API-Key", app1.apiKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        mvc.perform(get(PATH_EFFECTIVE).header("X-API-Key", app1.apiKey()).header("X-Context-Token", "   "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_CONTEXT_INVALID));

        // 5. 过期上下文拒绝 401 CONTEXT_INVALID；新签发后 DELETE /api/v1/contexts 主动撤销亦立即 401
        db.update("UPDATE permission_contexts SET expires_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minus(5, ChronoUnit.SECONDS)), userId);
        mvc.perform(get(PATH_EFFECTIVE).header("X-API-Key", app1.apiKey()).header("X-Context-Token", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_CONTEXT_INVALID));

        String token2 = parseJson(mvc.perform(post(PATH_CONTEXTS).header("X-API-Key", app1.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ContextIssueDTO(ext, dept, null, true))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("contextToken").asText();
        mvc.perform(delete(PATH_CONTEXTS).header("X-API-Key", app1.apiKey()).header("X-Context-Token", token2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get(PATH_EFFECTIVE).header("X-API-Key", app1.apiKey()).header("X-Context-Token", token2))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_CONTEXT_INVALID));
    }
}
