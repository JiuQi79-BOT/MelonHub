package com.melonhub.rbac.r24;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.admin.dto.AdminRoleCreateDTO;
import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.api.identity.dto.IdentityLoginDTO;
import com.melonhub.rbac.api.identity.dto.IdentityUserImportDTO;
import com.melonhub.rbac.api.identity.dto.IdentityUserRowDTO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.api.shared.dto.IdsDTO;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R24 统一身份 /auth/me、单权限(严格2字段)、operationId 校验、组织树/成员、用户资料与凭据批量导入 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R24_IdentityQueryControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、目标系统编码、课程权限码、operationId 与预期错误码）
    // =========================================================================
    private static final String PATH_ID_LOGIN = "/auth/login";
    private static final String PATH_ME = "/auth/me";
    private static final String PATH_PERM = "/auth/permissions/{permission}";
    private static final String PATH_API_PERM = "/auth/api-permissions/{operationId}";
    private static final String PATH_DEPTS = "/auth/departments";
    private static final String PATH_DEPT_USERS = "/auth/departments/{departmentId}/users";
    private static final String PATH_USER_LOOKUP = "/auth/users/{userId}";
    private static final String PATH_IDENTITY_IMPORT = "/api/admin/identity/users/import";
    private static final String PATH_USER_STATUS = "/api/admin/users/{id}/status";
    private static final String PATH_ROLE_PERMS = "/api/admin/roles/{id}/permissions";
    private static final String PATH_ROLE_STATUS = "/api/admin/roles/{id}/status";
    private static final String PATH_USER_ROLES = "/api/admin/users/{id}/roles";
    private static final String PATH_ADMIN_ROLES = "/api/admin/admin-roles";

    private static final String SYSTEM_COURSE = "course-project-management";
    private static final String USER_PASS = "CourseTeacher!2026";
    private static final String OP_CREATE_COURSE = "createCourse";
    private static final String OP_REVIEW_COURSE = "reviewCourse";
    private static final String OP_UNKNOWN = "unknownOp";
    private static final long MISSING_DEPT_ID = 9_999_999L;
    private static final String MISSING_USER_ID = "T_NOT_FOUND";

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_OP_NOT_FOUND = "API_OPERATION_NOT_FOUND";
    private static final String ERR_DEPT_NOT_FOUND = "IDENTITY_DEPARTMENT_NOT_FOUND";
    private static final String ERR_USER_NOT_FOUND = "IDENTITY_USER_NOT_FOUND";

    private long ensureCourseAppId() throws Exception {
        List<Long> found = db.queryForList("SELECT id FROM applications WHERE code = ?", Long.class, SYSTEM_COURSE);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return createApplication("课程项目管理平台", SYSTEM_COURSE).id();
    }

    @Test
    @DisplayName("R24-01 批量导入联调用户后真实登录，验证 /auth/me、单权限严格2字段、operationId、组织树/成员/用户资料与权限控制")
    void identityImportAndAllQueryEndpointsEndToEnd() throws Exception {
        long courseAppId = ensureCourseAppId();
        long deptId = createDepartment("计算机学院-" + tag, unique("cs"));
        String teacherNo = unique("T");
        String studentNo = unique("S");

        // 1. 通过 /api/admin/identity/users/import 批量导入教师与学生
        var importDto = new IdentityUserImportDTO(List.of(
                new IdentityUserRowDTO("张老师", teacherNo, USER_PASS, "t@edu.cn", List.of(deptId)),
                new IdentityUserRowDTO("李同学", studentNo, USER_PASS, "s@edu.cn", List.of(deptId))
        ));
        mvc.perform(post(PATH_IDENTITY_IMPORT).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(importDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
        // 已停用存量用户再次经统一身份批量导入应原地更新而非触发 409 DUPLICATE
        long studentId = db.queryForObject("SELECT id FROM users WHERE external_id = ?", Long.class, studentNo);
        mvc.perform(patch(PATH_USER_STATUS, studentId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(false))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_IDENTITY_IMPORT).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityUserImportDTO(List.of(
                                new IdentityUserRowDTO("李同学", studentNo, USER_PASS, "s@edu.cn", List.of(deptId)))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(patch(PATH_USER_STATUS, studentId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(true))))
                .andExpect(status().isOk());

        // 非法导入 DTO（空列表、空白密码）-> 400；缺 user.credentials -> 403
        mvc.perform(post(PATH_IDENTITY_IMPORT).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdentityUserImportDTO(List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        mvc.perform(post(PATH_IDENTITY_IMPORT).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityUserImportDTO(List.of(new IdentityUserRowDTO("空密", unique("x"), "   ", "", List.of(deptId)))))))
                .andExpect(status().isBadRequest());
        String onlyManageRole = ("OM_" + tag).toUpperCase();
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(onlyManageRole, "仅查用户", "", List.of("org.view", "user.status")))))
                .andExpect(status().isOk());
        var onlyManageAdmin = createAdminAndLogin("om", onlyManageRole, AdminPrincipal.MANAGER, null, null);
        mvc.perform(post(PATH_IDENTITY_IMPORT).header("Authorization", onlyManageAdmin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(importDto)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));

        // 2. 在 courseAppId 隔离域为教师配置角色与权限（CV, CE, MCO, ORG_VIEW, TEC）
        long teacherId = db.queryForObject("SELECT id FROM users WHERE external_id = ?", Long.class, teacherNo);
        long pCv = createPermission(deptId, courseAppId, "查看课程", "CV", "course", "view");
        long pCe = createPermission(deptId, courseAppId, "编辑课程", "CE", "course", "edit");
        long pMco = createPermission(deptId, courseAppId, "管理课程", "MCO", "course", "manage");
        long pOrg = createPermission(deptId, courseAppId, "查组织", "ORG_VIEW", "org", "view");
        long pTec = createPermission(deptId, courseAppId, "查用户", "TEC", "user", "view");
        long teacherRole = createRole(deptId, courseAppId, "任课教师", "Teacher", "");
        mvc.perform(put(PATH_ROLE_PERMS, teacherRole).param("departmentId", String.valueOf(deptId)).param("applicationId", String.valueOf(courseAppId))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdsDTO(List.of(pCv, pCe, pMco, pOrg, pTec)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_USER_ROLES, teacherId).param("departmentId", String.valueOf(deptId)).param("applicationId", String.valueOf(courseAppId))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(teacherRole), null, null))))
                .andExpect(status().isOk());

        // 3. 教师真实登录获取 JWT，调用 /auth/me（角色小写归一化）、单权限、operationId、组织与用户查询
        String jwt = parseJson(mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(teacherNo, USER_PASS, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        String bearer = "Bearer " + jwt;

        mvc.perform(get(PATH_ME).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(teacherNo))
                .andExpect(jsonPath("$.roles", hasItem("teacher")))
                .andExpect(jsonPath("$.permissions", hasItem("CE")));

        // /auth/permissions/{permission}：严格仅 2 字段
        mvc.perform(get(PATH_PERM, "MCO").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(2)))
                .andExpect(jsonPath("$.permission").value("MCO"))
                .andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(get(PATH_PERM, "CR").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false));

        // /auth/api-permissions/{operationId}
        mvc.perform(get(PATH_API_PERM, OP_CREATE_COURSE).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(get(PATH_API_PERM, OP_REVIEW_COURSE).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false));
        mvc.perform(get(PATH_API_PERM, OP_UNKNOWN).header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_OP_NOT_FOUND));

        // /auth/departments（含 parentId 层级展开 hasChildren=true 与分页边界钳制）& /auth/departments/{id}/users & /auth/users/{userId}
        long childDeptId = createDepartment("软件工程系-" + tag, unique("se"));
        db.update("UPDATE departments SET parent_id = ? WHERE id = ?", deptId, childDeptId);
        mvc.perform(get(PATH_DEPTS).param("keyword", "计算机学院-" + tag).param("page", "0").param("pageSize", "101").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(100))
                .andExpect(jsonPath("$.items[0].id").value(deptId))
                .andExpect(jsonPath("$.items[0].hasChildren").value(true));
        mvc.perform(get(PATH_DEPTS).param("parentId", String.valueOf(deptId)).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(childDeptId))
                .andExpect(jsonPath("$.items[0].parentId").value(deptId));
        mvc.perform(get(PATH_DEPT_USERS, deptId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].userId", hasItem(teacherNo)));
        mvc.perform(get(PATH_DEPT_USERS, MISSING_DEPT_ID).header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_DEPT_NOT_FOUND));
        mvc.perform(get(PATH_USER_LOOKUP, studentNo).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("李同学"));
        mvc.perform(get(PATH_USER_LOOKUP, MISSING_USER_ID).header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_USER_NOT_FOUND));

        // 4. 停用角色与到期分配在 /auth/me 与 /auth/permissions/{permission} 实时剔除
        mvc.perform(patch(PATH_ROLE_STATUS, teacherRole).param("departmentId", String.valueOf(deptId))
                        .param("applicationId", String.valueOf(courseAppId)).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(false))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ME).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", not(hasItem("MCO"))));
        mvc.perform(get(PATH_PERM, "MCO").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false));
        mvc.perform(patch(PATH_ROLE_STATUS, teacherRole).param("departmentId", String.valueOf(deptId))
                        .param("applicationId", String.valueOf(courseAppId)).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(true))))
                .andExpect(status().isOk());
        db.update("UPDATE user_roles SET expires_at = TIMESTAMPADD(SECOND, -5, CURRENT_TIMESTAMP) WHERE user_id = ? AND role_id = ?",
                teacherId, teacherRole);
        mvc.perform(get(PATH_PERM, "MCO").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false));

        // 5. 未登录 401（全部查询端点）与学生无 ORG_VIEW / TEC 权限 403
        mvc.perform(get(PATH_ME)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH_PERM, "MCO")).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH_API_PERM, OP_CREATE_COURSE)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH_DEPTS)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH_DEPT_USERS, deptId)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH_USER_LOOKUP, studentNo)).andExpect(status().isUnauthorized());

        String stuJwt = parseJson(mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(studentNo, USER_PASS, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(get(PATH_DEPTS).header("Authorization", "Bearer " + stuJwt)).andExpect(status().isForbidden());
        mvc.perform(get(PATH_DEPT_USERS, deptId).header("Authorization", "Bearer " + stuJwt)).andExpect(status().isForbidden());
        mvc.perform(get(PATH_USER_LOOKUP, teacherNo).header("Authorization", "Bearer " + stuJwt)).andExpect(status().isForbidden());
    }
}
