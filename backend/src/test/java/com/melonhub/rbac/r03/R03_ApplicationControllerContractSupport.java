package com.melonhub.rbac.r03;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.application.dto.ApplicationDTO;
import com.melonhub.rbac.api.context.dto.ContextIssueDTO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R3 业务软件接入管理（增/查/改/启/停/删、默认密钥、停用清上下文、依赖冲突与可见范围） —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R03_ApplicationControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、边界长度、非法 DTO 与预期错误码）
    // =========================================================================
    private static final String PATH_APPS = "/api/admin/applications";
    private static final String PATH_APP_BY_ID = "/api/admin/applications/{id}";
    private static final String PATH_APP_STATUS = "/api/admin/applications/{id}/status";
    private static final String PATH_V1_CONTEXTS = "/api/v1/contexts";
    private static final String PATH_V1_EFFECTIVE = "/api/v1/contexts/effective";

    private static final long MISSING_APP_ID = 9_999_999L;
    private static final String BOUNDARY_NAME_100 = "软".repeat(100);
    private static final String BOUNDARY_DESC_500 = "描".repeat(500);
    private static final String BOUNDARY_OWNER_100 = "负".repeat(100);
    private static final String INVALID_NAME_101 = "软".repeat(101);
    private static final String INVALID_CODE_65 = "a".repeat(65);
    private static final String INVALID_CODE_CHARS = "bad app!@#";
    private static final String INVALID_DESC_501 = "描".repeat(501);
    private static final String INVALID_OWNER_101 = "负".repeat(101);

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_DUPLICATE = "DUPLICATE";
    private static final String ERR_CONFLICT = "CONFLICT";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_UNAUTHORIZED = "UNAUTHORIZED";
    private static final String ERR_CONTEXT_INVALID = "CONTEXT_INVALID";

    @Test
    @DisplayName("R3-01 软件新建（自动签发默认 API Key 与边界长度）、列表范围过滤、修改、启停清上下文与空软件删除")
    void applicationFullLifecycleAndDisableRevokesContexts() throws Exception {
        String code1 = unique("app1");
        String maxCode64 = (unique("mx") + "a".repeat(64)).substring(0, 64);

        // 1. 新建软件：正常 + 边界长度（100/64/500/100）
        String created1 = mvc.perform(post(PATH_APPS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ApplicationDTO("财务系统", code1, "说明", "张三"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andExpect(jsonPath("$.keyId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long app1Id = parseJson(created1).get("id").asLong();
        String app1Key = parseJson(created1).get("apiKey").asText();

        String createdMax = mvc.perform(post(PATH_APPS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ApplicationDTO(BOUNDARY_NAME_100, maxCode64, BOUNDARY_DESC_500, BOUNDARY_OWNER_100))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long appMaxId = parseJson(createdMax).get("id").asLong();

        // 2. 列表过滤：超管见全部，软件管理员仅见被授权软件
        var appAdmin = createAppAdmin(List.of(app1Id));
        mvc.perform(get(PATH_APPS).header("Authorization", appAdmin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) app1Id)))
                .andExpect(jsonPath("$[*].id", not(hasItem((int) appMaxId))));

        // 3. 修改软件 + 不存在 404
        String updatedCode = unique("app1-u");
        mvc.perform(put(PATH_APP_BY_ID, app1Id).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ApplicationDTO("财务新名", updatedCode, "新说明", "李四"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(app1Id));
        mvc.perform(put(PATH_APP_BY_ID, MISSING_APP_ID).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ApplicationDTO("缺失", unique("ms"), "", ""))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));

        // 4. 停用软件 -> 立即清除该软件全部权限上下文且拒绝新签发；重新启用后可再签发
        long dept = createDepartment("软件部", unique("sd"));
        String ext = unique("su");
        createUser("员工", ext, "", List.of(dept));
        String ctxBody = mvc.perform(post(PATH_V1_CONTEXTS).header("X-API-Key", app1Key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, dept, null, true))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ctxToken = parseJson(ctxBody).get("contextToken").asText();

        mvc.perform(patch(PATH_APP_STATUS, app1Id).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(false))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app1Key).header("X-Context-Token", ctxToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_UNAUTHORIZED));
        mvc.perform(post(PATH_V1_CONTEXTS).header("X-API-Key", app1Key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, dept, null, true))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_UNAUTHORIZED));

        mvc.perform(patch(PATH_APP_STATUS, app1Id).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(true))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app1Key).header("X-Context-Token", ctxToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_CONTEXT_INVALID));
        mvc.perform(post(PATH_V1_CONTEXTS).header("X-API-Key", app1Key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, dept, null, true))))
                .andExpect(status().isOk());

        // 5. 删除空软件成功，再次删除 404
        mvc.perform(delete(PATH_APP_BY_ID, appMaxId).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(delete(PATH_APP_BY_ID, appMaxId).header("Authorization", superBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("R3-02 非法 DTO 返回 400，无权角色返回 403，含角色/权限的软件删除返回 409 并列明依赖")
    void validationPermissionsAndDeleteConflict() throws Exception {
        for (ApplicationDTO bad : List.of(
                new ApplicationDTO(" ", unique("b"), "", ""),
                new ApplicationDTO(INVALID_NAME_101, unique("b"), "", ""),
                new ApplicationDTO("A", " ", "", ""),
                new ApplicationDTO("A", INVALID_CODE_65, "", ""),
                new ApplicationDTO("A", INVALID_CODE_CHARS, "", ""),
                new ApplicationDTO("A", unique("b"), INVALID_DESC_501, ""),
                new ApplicationDTO("A", unique("b"), "", INVALID_OWNER_101)
        )) {
            mvc.perform(post(PATH_APPS).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }

        var auditor = createAuditor();
        mvc.perform(post(PATH_APPS).header("Authorization", auditor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ApplicationDTO("越权", unique("au"), "", ""))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));

        long dept = createDepartment("依赖部", unique("dd"));
        var app = createApplication("有依赖软件", unique("depa"));
        createRole(dept, app.id(), "角色", "r1", "");
        createPermission(dept, app.id(), "权限", "p:1", "p", "read");

        mvc.perform(delete(PATH_APP_BY_ID, app.id()).header("Authorization", superBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT))
                .andExpect(jsonPath("$.message", containsString("1 个角色")))
                .andExpect(jsonPath("$.message", containsString("1 个权限")));
    }

    @Test
    @DisplayName("R3-03 重复软件编码返回 409 DUPLICATE")
    void duplicateApplicationCodeReturns409() throws Exception {
        String code = unique("dup-app");
        createApplication("软件一", code);
        mvc.perform(post(PATH_APPS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ApplicationDTO("软件二", code, "", ""))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_DUPLICATE));
    }
}
