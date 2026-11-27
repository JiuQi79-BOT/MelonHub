package com.melonhub.rbac.r02;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.context.dto.ContextIssueDTO;
import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.api.organization.dto.UserCreateDTO;
import com.melonhub.rbac.api.organization.dto.UserDTO;
import com.melonhub.rbac.api.organization.dto.UserImportDTO;
import com.melonhub.rbac.api.organization.dto.UserImportRowDTO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R2 用户与多部门成员管理、强制下线、权限总览与 R2a 批量导入 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R02_UserControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、用户边界字段、批量导入行与预期错误码）
    // =========================================================================
    private static final String PATH_USERS = "/api/admin/users";
    private static final String PATH_USERS_EXPORT = "/api/admin/users/export";
    private static final String PATH_USERS_IMPORT = "/api/admin/users/import";
    private static final String PATH_USER_BY_ID = "/api/admin/users/{id}";
    private static final String PATH_USER_STATUS = "/api/admin/users/{id}/status";
    private static final String PATH_USER_REVOKE = "/api/admin/users/{id}/contexts";
    private static final String PATH_USER_ACCESS = "/api/admin/users/{id}/access";
    private static final String PATH_USER_ROLES = "/api/admin/users/{id}/roles";
    private static final String PATH_V1_DEPT_USERS = "/api/v1/departments/{dept}/users";
    private static final String PATH_V1_CONTEXTS = "/api/v1/contexts";
    private static final String PATH_V1_EFFECTIVE = "/api/v1/contexts/effective";

    private static final long MISSING_USER_ID = 9_999_999L;
    private static final int OVER_MAX_IMPORT_ROWS_501 = 501;
    private static final String BOUNDARY_NAME_100 = "员".repeat(100);
    private static final String BOUNDARY_EMAIL_150 = "a".repeat(138) + "@example.com";
    private static final String INVALID_NAME_101 = "员".repeat(101);
    private static final String INVALID_EXT_101 = "e".repeat(101);
    private static final String INVALID_EMAIL_151 = "a".repeat(139) + "@example.com";

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_DUPLICATE = "DUPLICATE";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_CONTEXT_INVALID = "CONTEXT_INVALID";

    private String issueToken(String apiKey, String externalId, long deptId) throws Exception {
        String body = mvc.perform(post(PATH_V1_CONTEXTS).header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(externalId, deptId, null, true))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return parseJson(body).get("contextToken").asText();
    }

    @Test
    @DisplayName("R2-01 用户分页/筛选/导出、新建（含边界）、修改外部标识与移出部门清理遗留角色与上下文")
    void createListExportUpdateAndDepartmentRemovalCleanup() throws Exception {
        long d1 = createDepartment("研发部", unique("d1"));
        long d2 = createDepartment("测试部", unique("d2"));
        var app = createApplication("UserApp", unique("ua"));
        long r2 = createRole(d2, app.id(), "测试角色", "qa", "");
        String ext = unique("emp");
        String extMax = (unique("max") + "x".repeat(100)).substring(0, 100);

        // 1. 新建（正常 + 最大边界 100/100/150 + 无部门成员）
        String createdBody = mvc.perform(post(PATH_USERS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserCreateDTO("张三", ext, "zs@example.com", List.of(d1, d2), null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long userId = parseJson(createdBody).get("id").asLong();

        mvc.perform(post(PATH_USERS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserCreateDTO(BOUNDARY_NAME_100, extMax, BOUNDARY_EMAIL_150, List.of(), null))))
                .andExpect(status().isOk());

        // 给 userId 在 d2 授权并签发上下文
        mvc.perform(put(PATH_USER_ROLES, userId).param("departmentId", String.valueOf(d2))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(r2), null, null))))
                .andExpect(status().isOk());
        String tokenD2 = issueToken(app.apiKey(), ext, d2);

        // 2. 分页、边界钳制（page=0->1, size=999->200）、导出（上限5000）与业务端部门成员查询
        mvc.perform(get(PATH_USERS).param("keyword", ext).param("departmentId", String.valueOf(d1))
                        .param("enabled", "true").param("page", "0").param("size", "999")
                        .header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(200))
                .andExpect(jsonPath("$.items[0].externalId").value(ext));
        mvc.perform(get(PATH_USERS_EXPORT).param("keyword", ext).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(Integer.MAX_VALUE))
                .andExpect(jsonPath("$.items[0].externalId").value(ext));
        mvc.perform(get(PATH_V1_DEPT_USERS, d1).header("X-API-Key", app.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].externalId", hasItem(ext)));

        // 3. 权限总览应含 d2 角色
        mvc.perform(get(PATH_USER_ACCESS, userId).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domains[0].roles[0].code").value("qa"));

        // 3.5 仅修改姓名/邮箱（externalId 与部门未变）-> 现有上下文保持有效 200
        mvc.perform(put(PATH_USER_BY_ID, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserDTO("张三仅改名", ext, "zs-keep@example.com", List.of(d1, d2)))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app.apiKey()).header("X-Context-Token", tokenD2))
                .andExpect(status().isOk());

        // 4. 移出 d2 部门并修改 externalId -> 旧上下文立即失效，且 d2 遗留角色已清除
        String extNew = unique("emp-new");
        mvc.perform(put(PATH_USER_BY_ID, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserDTO("张三改", extNew, "zs2@example.com", List.of(d1)))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app.apiKey()).header("X-Context-Token", tokenD2))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_CONTEXT_INVALID));
        mvc.perform(get(PATH_USER_ROLES, userId).param("departmentId", String.valueOf(d2))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleIds").isEmpty());
    }

    @Test
    @DisplayName("R2-02 用户启停、强制下线、删除及非法 DTO / 404 / 管理员部门范围越权 403")
    void statusRevokeDeleteValidationAndScope() throws Exception {
        long d1 = createDepartment("部门一", unique("s1"));
        long d2 = createDepartment("部门二", unique("s2"));
        var app = createApplication("StatusApp", unique("sa"));
        String ext = unique("st-u");
        long userId = createUser("李四", ext, "ls@example.com", List.of(d1));
        long userD2 = createUser("王五", unique("d2-u"), "", List.of(d2));

        // 1. 强制下线使上下文失效
        String tok1 = issueToken(app.apiKey(), ext, d1);
        mvc.perform(delete(PATH_USER_REVOKE, userId).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(1));
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app.apiKey()).header("X-Context-Token", tok1))
                .andExpect(status().isUnauthorized());

        // 2. 停用用户使上下文失效，重新启用恢复
        String tok2 = issueToken(app.apiKey(), ext, d1);
        mvc.perform(patch(PATH_USER_STATUS, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(false))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app.apiKey()).header("X-Context-Token", tok2))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(PATH_USER_STATUS, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(true))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app.apiKey()).header("X-Context-Token", tok2))
                .andExpect(status().isUnauthorized());

        // 3. 非法 DTO -> 400
        for (UserCreateDTO bad : List.of(
                new UserCreateDTO(" ", unique("b"), "", List.of(d1), null),
                new UserCreateDTO(INVALID_NAME_101, unique("b"), "", List.of(d1), null),
                new UserCreateDTO("名", " ", "", List.of(d1), null),
                new UserCreateDTO("名", INVALID_EXT_101, "", List.of(d1), null),
                new UserCreateDTO("名", unique("b"), INVALID_EMAIL_151, List.of(d1), null),
                new UserCreateDTO("名", unique("b"), "", null, null),
                new UserCreateDTO("名", unique("b"), "", List.of(0L), null)
        )) {
            mvc.perform(post(PATH_USERS).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }
        mvc.perform(patch(PATH_USER_STATUS, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(null))))
                .andExpect(status().isBadRequest());

        // 4. 部门管理员范围隔离：仅见 d1 成员，不可停用/查看/删除 d2 成员 -> 403
        var deptAdmin = createDeptAdmin(List.of(d1));
        mvc.perform(get(PATH_USERS).header("Authorization", deptAdmin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", hasItem((int) userId)))
                .andExpect(jsonPath("$.items[*].id", not(hasItem((int) userD2))));
        mvc.perform(patch(PATH_USER_STATUS, userD2).header("Authorization", deptAdmin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(false))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        mvc.perform(delete(PATH_USER_BY_ID, userD2).header("Authorization", deptAdmin.bearer()))
                .andExpect(status().isForbidden());

        // 5. 审计员不可写 403；不存在用户 404；删除成功后再查 404
        var auditor = createAuditor();
        mvc.perform(delete(PATH_USER_BY_ID, userId).header("Authorization", auditor.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(PATH_USER_BY_ID, MISSING_USER_ID).header("Authorization", superBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));
        mvc.perform(delete(PATH_USER_BY_ID, userId).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_USER_ACCESS, userId).header("Authorization", superBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("R2-03 重复 externalId 返回 409 DUPLICATE")
    void duplicateExternalIdReturns409() throws Exception {
        long d1 = createDepartment("重复检测部", unique("dd"));
        String ext = unique("dup-ext");
        createUser("原用户", ext, "", List.of(d1));

        mvc.perform(post(PATH_USERS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserCreateDTO("冲突用户", ext, "", List.of(d1), null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_DUPLICATE));
    }

    @Test
    @DisplayName("R2a-01 用户批量导入：新建+按externalId更新并追加部门+逐行报错不中断+空列表400+非全部门管理员403")
    void bulkImportUsersCoversCreateUpdatePartialFailureValidationAndScope() throws Exception {
        String c1 = unique("imp-d1");
        String c2 = unique("imp-d2");
        long d1 = createDepartment("导入一部", c1);
        createDepartment("导入二部", c2);
        String existingExt = unique("imp-ex");
        String newExt = unique("imp-new");
        createUser("旧姓名", existingExt, "old@example.com", List.of(d1));

        var batch = new UserImportDTO(List.of(
                new UserImportRowDTO("新员工", newExt, "new@example.com", List.of(c1)),
                new UserImportRowDTO("更新姓名", existingExt, "upd@example.com", List.of(c2)),
                new UserImportRowDTO("坏部门行", unique("bad-d"), "", List.of("no-such-dept")),
                new UserImportRowDTO(" ", unique("bad-n"), "", List.of(c1))
        ));
        mvc.perform(post(PATH_USERS_IMPORT).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.failed.length()").value(2));

        // 验证已更新员工同时保留原部门 d1 并追加了新部门 d2
        mvc.perform(get(PATH_USERS).param("keyword", existingExt).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("更新姓名"))
                .andExpect(jsonPath("$.items[0].departmentIds.length()").value(2));

        // 空列表 / null / 超 500 行上限均 400；部门管理员（非全部门）403
        List<UserImportRowDTO> over500Rows = java.util.stream.IntStream.range(0, OVER_MAX_IMPORT_ROWS_501)
                .mapToObj(i -> new UserImportRowDTO("批量" + i, unique("ov" + i), "", List.of(c1)))
                .toList();
        for (UserImportDTO badImport : List.of(new UserImportDTO(List.of()), new UserImportDTO(null), new UserImportDTO(over500Rows))) {
            mvc.perform(post(PATH_USERS_IMPORT).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(badImport)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }
        var deptAdmin = createDeptAdmin(List.of(d1));
        mvc.perform(post(PATH_USERS_IMPORT).header("Authorization", deptAdmin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(batch)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
    }
}
