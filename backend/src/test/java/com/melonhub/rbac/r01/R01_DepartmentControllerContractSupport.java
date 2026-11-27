package com.melonhub.rbac.r01;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.organization.dto.DepartmentDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R1 部门与组织架构管理 —— 真实 MySQL 数据库 Controller 层全量正常/边界/非法/依赖冲突/作用域测试。
 */
abstract class R01_DepartmentControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、合法/边界/非法部门参数、不存在 ID 与预期错误码）
    // =========================================================================
    private static final String PATH_ADMIN_DEPTS = "/api/admin/departments";
    private static final String PATH_ADMIN_DEPT_BY_ID = "/api/admin/departments/{id}";
    private static final String PATH_V1_DEPTS = "/api/v1/departments";

    private static final long MISSING_DEPT_ID = 9_999_999L;
    private static final String VALID_NAME = "研发中心";
    private static final String UPDATED_NAME = "基础架构中心";
    private static final String BOUNDARY_MIN_NAME = "部";
    private static final String BOUNDARY_MAX_NAME_100 = "部".repeat(100);
    private static final String INVALID_NAME_BLANK = "   ";
    private static final String INVALID_NAME_OVER_101 = "部".repeat(101);
    private static final String INVALID_CODE_BLANK = " ";
    private static final String INVALID_CODE_BAD_CHARS = "bad code!@#";
    private static final String INVALID_CODE_OVER_65 = "d".repeat(65);

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_DUPLICATE = "DUPLICATE";
    private static final String ERR_CONFLICT = "CONFLICT";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_UNAUTHORIZED = "UNAUTHORIZED";

    @Test
    @DisplayName("R1-01 部门新建、查询（管理端与业务端）、修改与空部门删除（含 1 字符及 100/64 最大边界）")
    void createListUpdateAndDeleteEmptyDepartment() throws Exception {
        String code1 = unique("dept");
        String codeUpdated = unique("dept-upd");
        String maxCode64 = (unique("m") + "x".repeat(64)).substring(0, 64);

        // 1. 正常新建与边界新建（1 字符 / 100 字符名 + 64 字符编码）
        String body1 = mvc.perform(post(PATH_ADMIN_DEPTS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DepartmentDTO(VALID_NAME, code1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn().getResponse().getContentAsString();
        long dept1 = parseJson(body1).get("id").asLong();

        String bodyMax = mvc.perform(post(PATH_ADMIN_DEPTS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DepartmentDTO(BOUNDARY_MAX_NAME_100, maxCode64))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long deptMax = parseJson(bodyMax).get("id").asLong();

        mvc.perform(post(PATH_ADMIN_DEPTS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DepartmentDTO(BOUNDARY_MIN_NAME, unique("a").substring(0, 8)))))
                .andExpect(status().isOk());

        // 2. 查询：管理端列表 + 业务端 API Key 列表
        var app = createApplication("R01App", unique("r01a"));
        mvc.perform(get(PATH_ADMIN_DEPTS).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem(code1)))
                .andExpect(jsonPath("$[*].code", hasItem(maxCode64)));
        mvc.perform(get(PATH_V1_DEPTS).header("X-API-Key", app.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem(code1)));

        // 3. 修改部门名称与编码 + 不存在部门 404
        mvc.perform(put(PATH_ADMIN_DEPT_BY_ID, dept1).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DepartmentDTO(UPDATED_NAME, codeUpdated))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dept1));
        mvc.perform(get(PATH_ADMIN_DEPTS).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem(codeUpdated)));
        mvc.perform(put(PATH_ADMIN_DEPT_BY_ID, MISSING_DEPT_ID).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DepartmentDTO(UPDATED_NAME, unique("miss")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));

        // 4. 删除空部门成功 + 再次删除 404
        mvc.perform(delete(PATH_ADMIN_DEPT_BY_ID, deptMax).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(delete(PATH_ADMIN_DEPT_BY_ID, deptMax).header("Authorization", superBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));
    }

    @Test
    @DisplayName("R1-02 非法 DTO（空名/超100名/空码/超64码/非法字符）返回 400，未认证 401，权限与部门范围 403")
    void validationAuthAndDepartmentScope() throws Exception {
        long d1 = createDepartment("部门甲", unique("d1"));
        long d2 = createDepartment("部门乙", unique("d2"));

        // 1. 全量非法 DTO -> 400 INVALID_REQUEST
        for (DepartmentDTO bad : List.of(
                new DepartmentDTO(INVALID_NAME_BLANK, unique("ok")),
                new DepartmentDTO(INVALID_NAME_OVER_101, unique("ok")),
                new DepartmentDTO(VALID_NAME, INVALID_CODE_BLANK),
                new DepartmentDTO(VALID_NAME, INVALID_CODE_BAD_CHARS),
                new DepartmentDTO(VALID_NAME, INVALID_CODE_OVER_65)
        )) {
            mvc.perform(post(PATH_ADMIN_DEPTS).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }

        // 2. 未登录 401
        mvc.perform(get(PATH_ADMIN_DEPTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_UNAUTHORIZED));

        // 3. 审计员只读：可列表不可写 403
        var auditor = createAuditor();
        mvc.perform(get(PATH_ADMIN_DEPTS).header("Authorization", auditor.bearer()))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_ADMIN_DEPTS).header("Authorization", auditor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DepartmentDTO(VALID_NAME, unique("aud")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        mvc.perform(delete(PATH_ADMIN_DEPT_BY_ID, d1).header("Authorization", auditor.bearer()))
                .andExpect(status().isForbidden());

        // 4. 部门管理员列表仅见管辖部门 d1，不可见 d2，且无权增删部门
        var deptAdmin = createDeptAdmin(List.of(d1));
        mvc.perform(get(PATH_ADMIN_DEPTS).header("Authorization", deptAdmin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) d1)))
                .andExpect(jsonPath("$[*].id", not(hasItem((int) d2))));
        mvc.perform(post(PATH_ADMIN_DEPTS).header("Authorization", deptAdmin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DepartmentDTO("越权部门", unique("unauth")))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R1-03 存在成员、角色、权限或限定部门管理员时删除部门返回 409 CONFLICT 并列明剩余依赖")
    void deleteDepartmentWithDependenciesReturnsConflict() throws Exception {
        long dept = createDepartment("有依赖部门", unique("dep"));
        var app = createApplication("DepApp", unique("da"));
        createUser("成员甲", unique("u"), "u@example.com", List.of(dept));
        createRole(dept, app.id(), "角色甲", "role-dep", "");
        createPermission(dept, app.id(), "权限甲", "perm:dep", "res", "act");
        createDeptAdmin(List.of(dept));

        mvc.perform(delete(PATH_ADMIN_DEPT_BY_ID, dept).header("Authorization", superBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT))
                .andExpect(jsonPath("$.message", containsString("1 名成员")))
                .andExpect(jsonPath("$.message", containsString("1 个角色")))
                .andExpect(jsonPath("$.message", containsString("1 个权限")))
                .andExpect(jsonPath("$.message", containsString("1 名限定于该部门的管理员")));
    }

    @Test
    @DisplayName("R1-04 重复部门编码在真实 MySQL 唯一索引下返回 409 DUPLICATE")
    void duplicateDepartmentCodeReturns409Duplicate() throws Exception {
        String dupCode = unique("dup");
        createDepartment("首次部门", dupCode);

        mvc.perform(post(PATH_ADMIN_DEPTS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new DepartmentDTO("重复编码部门", dupCode))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_DUPLICATE));
    }
}
