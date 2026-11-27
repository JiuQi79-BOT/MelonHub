package com.melonhub.rbac.r06;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.admin.dto.AdminRoleCreateDTO;
import com.melonhub.rbac.api.context.dto.ContextIssueDTO;
import com.melonhub.rbac.api.domainrbac.dto.PermissionDTO;
import com.melonhub.rbac.api.domainrbac.dto.RoleDTO;
import com.melonhub.rbac.api.governance.dto.AccessRequestSubmitDTO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.api.shared.dto.IdsDTO;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R6 角色、权限动态定义与角色成员维护 —— 真实 MySQL Controller 层全量穷尽正常/边界/非法/权限/联动作用于双策略测试。
 * 覆盖：角色增/查/改/启/停/删（含删角色自动撤回待审申请、停用实时生效）、权限增/查/改/删、角色权限绑定/替换/清空、
 * 角色成员查询/批量替换/清空（含管理端与业务端、domain.configure 与 domain.assign 权限分离）。
 */
abstract class R06_RolePermissionControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、正常/边界/非法角色与权限载荷、超限列表与预期错误码）
    // =========================================================================
    private static final String PATH_ADMIN_ROLES = "/api/admin/roles";
    private static final String PATH_ADMIN_ROLE_BY_ID = "/api/admin/roles/{id}";
    private static final String PATH_ADMIN_ROLE_STATUS = "/api/admin/roles/{id}/status";
    private static final String PATH_ADMIN_ROLE_PERMS = "/api/admin/roles/{id}/permissions";
    private static final String PATH_ADMIN_ROLE_MEMBERS = "/api/admin/roles/{id}/members";
    private static final String PATH_ADMIN_PERMS = "/api/admin/permissions";
    private static final String PATH_ADMIN_PERM_BY_ID = "/api/admin/permissions/{id}";
    private static final String PATH_ADMIN_CUSTOM_ROLES = "/api/admin/admin-roles";
    private static final String PATH_V1_ROLES = "/api/v1/departments/{dept}/roles";
    private static final String PATH_V1_ROLE_BY_ID = "/api/v1/departments/{dept}/roles/{id}";
    private static final String PATH_V1_ROLE_STATUS = "/api/v1/departments/{dept}/roles/{id}/status";
    private static final String PATH_V1_ROLE_PERMS = "/api/v1/departments/{dept}/roles/{id}/permissions";
    private static final String PATH_V1_PERMS = "/api/v1/departments/{dept}/permissions";
    private static final String PATH_V1_PERM_BY_ID = "/api/v1/departments/{dept}/permissions/{id}";
    private static final String PATH_V1_CONTEXTS = "/api/v1/contexts";
    private static final String PATH_V1_EFFECTIVE = "/api/v1/contexts/effective";
    private static final String PATH_V1_REQUESTS = "/api/v1/access-requests";

    private static final long MISSING_USER_ID = 9_999_999L;
    private static final String VALID_ROLE_NAME = "订单审核员";
    private static final String VALID_ROLE_CODE = "order:reviewer_v1.0-cn";
    private static final String VALID_ROLE_DESC = "负责审核部门订单";
    private static final String BOUNDARY_NAME_100 = "角".repeat(100);
    private static final String BOUNDARY_CODE_100 = "r".repeat(100);
    private static final String BOUNDARY_DESC_500 = "说".repeat(500);
    private static final String INVALID_NAME_101 = "角".repeat(101);
    private static final String INVALID_CODE_101 = "r".repeat(101);
    private static final String INVALID_CODE_CHARS = "bad role!@#";
    private static final String INVALID_DESC_501 = "说".repeat(501);

    private static final String VALID_PERM_NAME = "审批订单";
    private static final String VALID_PERM_CODE = "order:approve";
    private static final String VALID_RESOURCE = "order";
    private static final String VALID_ACTION = "approve";
    private static final String BOUNDARY_RES_100 = "r".repeat(100);
    private static final String BOUNDARY_ACT_64 = "a".repeat(64);
    private static final String INVALID_RES_101 = "r".repeat(101);
    private static final String INVALID_ACT_65 = "a".repeat(65);
    private static final int OVER_MAX_IDS_1001 = 1001;

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_DUPLICATE = "DUPLICATE";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_CONTEXT_INVALID = "CONTEXT_INVALID";

    @Test
    @DisplayName("R6-01 角色增/查/改/启/停/删（管理端+业务端、最小/最大边界、非法DTO400、停用实时生效、删角色撤回待审申请）")
    void roleCrudEnableDisableAndDeleteCascade() throws Exception {
        long dept = createDepartment("订单部", unique("ord"));
        var app = createApplication("OrderApp", unique("oa"));
        String ext = unique("ord-u");
        long userId = createUser("审核员", ext, "", List.of(dept));
        long permId = createPermission(dept, app.id(), VALID_PERM_NAME, VALID_PERM_CODE, VALID_RESOURCE, VALID_ACTION);

        // 1. 新建角色：正常 + 边界最大长度(100/100/500) + 业务端新建最小1字符
        String r1Body = mvc.perform(post(PATH_ADMIN_ROLES).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RoleDTO(VALID_ROLE_NAME, VALID_ROLE_CODE, VALID_ROLE_DESC))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long roleId = parseJson(r1Body).get("id").asLong();

        mvc.perform(post(PATH_ADMIN_ROLES).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RoleDTO(BOUNDARY_NAME_100, BOUNDARY_CODE_100, BOUNDARY_DESC_500))))
                .andExpect(status().isOk());
        long v1RoleId = parseJson(mvc.perform(post(PATH_V1_ROLES, dept).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RoleDTO("R", "r", null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(put(PATH_V1_ROLE_BY_ID, dept, v1RoleId).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RoleDTO("R改", "r-upd", "业务端修改"))))
                .andExpect(status().isOk());
        mvc.perform(delete(PATH_V1_ROLE_BY_ID, dept, v1RoleId).header("X-API-Key", app.apiKey()))
                .andExpect(status().isOk());

        // 2. 查询角色（管理端 + 业务端）
        mvc.perform(get(PATH_ADMIN_ROLES).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem(VALID_ROLE_CODE)));
        mvc.perform(get(PATH_V1_ROLES, dept).header("X-API-Key", app.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem(VALID_ROLE_CODE)));

        // 3. 修改角色 + 绑权 + 分配给成员，再验证启停实时生效
        mvc.perform(put(PATH_ADMIN_ROLE_BY_ID, roleId).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RoleDTO("高级订单审核员", VALID_ROLE_CODE, "新描述"))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_ADMIN_ROLE_PERMS, roleId).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(permId)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_ADMIN_ROLE_MEMBERS, roleId).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(userId)))))
                .andExpect(status().isOk());

        String ctxBody = mvc.perform(post(PATH_V1_CONTEXTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext, dept, null, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", hasItem(VALID_PERM_CODE)))
                .andReturn().getResponse().getContentAsString();
        String ctxToken = parseJson(ctxBody).get("contextToken").asText();

        // 停用角色 -> 下一次校验立即不再包含该权限；业务端重新启用 -> 权限恢复
        mvc.perform(patch(PATH_ADMIN_ROLE_STATUS, roleId).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(false))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app.apiKey()).header("X-Context-Token", ctxToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", not(hasItem(VALID_PERM_CODE))));
        mvc.perform(patch(PATH_V1_ROLE_STATUS, dept, roleId).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(true))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app.apiKey()).header("X-Context-Token", ctxToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", hasItem(VALID_PERM_CODE)));

        // 4. 非法 DTO -> 400
        for (RoleDTO bad : List.of(
                new RoleDTO(" ", VALID_ROLE_CODE, ""),
                new RoleDTO(INVALID_NAME_101, VALID_ROLE_CODE, ""),
                new RoleDTO(VALID_ROLE_NAME, " ", ""),
                new RoleDTO(VALID_ROLE_NAME, INVALID_CODE_101, ""),
                new RoleDTO(VALID_ROLE_NAME, INVALID_CODE_CHARS, ""),
                new RoleDTO(VALID_ROLE_NAME, VALID_ROLE_CODE, INVALID_DESC_501)
        )) {
            mvc.perform(post(PATH_ADMIN_ROLES).param("departmentId", String.valueOf(dept))
                            .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }
        mvc.perform(patch(PATH_ADMIN_ROLE_STATUS, roleId).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(null))))
                .andExpect(status().isBadRequest());

        // 5. 删除角色自动撤回该角色的待审批申请
        String ext2 = unique("req-u");
        createUser("申请人", ext2, "", List.of(dept));
        mvc.perform(post(PATH_V1_REQUESTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AccessRequestSubmitDTO(ext2, dept, roleId, "申请审核角色", 7))))
                .andExpect(status().isOk());
        mvc.perform(delete(PATH_ADMIN_ROLE_BY_ID, roleId).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_REQUESTS).param("externalId", ext2).header("X-API-Key", app.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));
    }

    @Test
    @DisplayName("R6-02 权限项增/查/改/删（含边界与非法 DTO 400）与角色权限关联绑定/替换/清空及权限边界 403")
    void permissionCrudAndRolePermissionBinding() throws Exception {
        long dept = createDepartment("权限部", unique("prm"));
        var app = createApplication("PermApp", unique("pa"));
        long roleId = createRole(dept, app.id(), "操作员", "operator", "");

        // 1. 新建权限：正常 + 最大边界(100/100/100/64) + 业务端最小 1 字符
        String p1Body = mvc.perform(post(PATH_ADMIN_PERMS).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PermissionDTO(VALID_PERM_NAME, VALID_PERM_CODE, VALID_RESOURCE, VALID_ACTION))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long p1 = parseJson(p1Body).get("id").asLong();

        mvc.perform(post(PATH_ADMIN_PERMS).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PermissionDTO(BOUNDARY_NAME_100, BOUNDARY_CODE_100, BOUNDARY_RES_100, BOUNDARY_ACT_64))))
                .andExpect(status().isOk());
        String p2Body = mvc.perform(post(PATH_V1_PERMS, dept).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PermissionDTO("P", "p", "r", "a"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long p2 = parseJson(p2Body).get("id").asLong();

        // 2. 查询（管理端 + 业务端）与修改
        mvc.perform(get(PATH_ADMIN_PERMS).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem(VALID_PERM_CODE)));
        mvc.perform(get(PATH_V1_PERMS, dept).header("X-API-Key", app.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("p")));
        mvc.perform(put(PATH_ADMIN_PERM_BY_ID, p1).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PermissionDTO("改名审批", VALID_PERM_CODE, "order", "review"))))
                .andExpect(status().isOk());

        // 3. 非法权限 DTO -> 400
        for (PermissionDTO bad : List.of(
                new PermissionDTO(" ", VALID_PERM_CODE, VALID_RESOURCE, VALID_ACTION),
                new PermissionDTO(INVALID_NAME_101, VALID_PERM_CODE, VALID_RESOURCE, VALID_ACTION),
                new PermissionDTO(VALID_PERM_NAME, " ", VALID_RESOURCE, VALID_ACTION),
                new PermissionDTO(VALID_PERM_NAME, INVALID_CODE_101, VALID_RESOURCE, VALID_ACTION),
                new PermissionDTO(VALID_PERM_NAME, INVALID_CODE_CHARS, VALID_RESOURCE, VALID_ACTION),
                new PermissionDTO(VALID_PERM_NAME, VALID_PERM_CODE, null, VALID_ACTION),
                new PermissionDTO(VALID_PERM_NAME, VALID_PERM_CODE, VALID_RESOURCE, " "),
                new PermissionDTO(VALID_PERM_NAME, VALID_PERM_CODE, INVALID_RES_101, VALID_ACTION),
                new PermissionDTO(VALID_PERM_NAME, VALID_PERM_CODE, VALID_RESOURCE, INVALID_ACT_65)
        )) {
            mvc.perform(post(PATH_ADMIN_PERMS).param("departmentId", String.valueOf(dept))
                            .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }

        // 4. 角色权限绑定（管理端 + 业务端）、清空空数组、非法 IdsDTO(null/0/-1/>1000) 400、审计员 403
        mvc.perform(put(PATH_ADMIN_ROLE_PERMS, roleId).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(p1, p2)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_V1_ROLE_PERMS, dept, roleId).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(p1)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_ADMIN_ROLE_PERMS, roleId).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of()))))
                .andExpect(status().isOk());

        List<Long> over1001 = LongStream.rangeClosed(1, OVER_MAX_IDS_1001).boxed().toList();
        for (IdsDTO badIds : List.of(new IdsDTO(null), new IdsDTO(List.of(0L)), new IdsDTO(List.of(-1L)), new IdsDTO(over1001))) {
            mvc.perform(put(PATH_ADMIN_ROLE_PERMS, roleId).param("departmentId", String.valueOf(dept))
                            .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(badIds)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }
        var auditor = createAuditor();
        mvc.perform(put(PATH_ADMIN_ROLE_PERMS, roleId).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", auditor.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(p1)))))
                .andExpect(status().isForbidden());

        // 5. 业务端权限修改/删除（含非法 DTO 400 与跨部门 403）及管理端删除权限
        long otherDept = createDepartment("权限外部门", unique("pout"));
        mvc.perform(put(PATH_V1_PERM_BY_ID, dept, p2).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PermissionDTO("业务改权", "p-upd", "r", "upd"))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_V1_PERM_BY_ID, dept, p2).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PermissionDTO(" ", "p-upd", "r", "upd"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        mvc.perform(put(PATH_V1_PERM_BY_ID, otherDept, p2).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PermissionDTO("跨域改权", "p-upd", "r", "upd"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete(PATH_V1_PERM_BY_ID, otherDept, p2).header("X-API-Key", app.apiKey()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(PATH_V1_PERM_BY_ID, dept, p2).header("X-API-Key", app.apiKey()))
                .andExpect(status().isOk());
        mvc.perform(delete(PATH_ADMIN_PERM_BY_ID, p1).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("R6-03 角色侧批量维护成员：增删成员撤销受影响上下文、清空、非成员403、不存在用户404、缺 domain.assign 403")
    void roleMembersBatchMaintenanceAndPermissionSeparation() throws Exception {
        long d1 = createDepartment("成员一部", unique("m1"));
        long d2 = createDepartment("成员二部", unique("m2"));
        var app = createApplication("MemApp", unique("ma"));
        long roleId = createRole(d1, app.id(), "财务审核", "fin-rev", "");
        String ext1 = unique("mu1");
        String ext2 = unique("mu2");
        long u1 = createUser("成员一", ext1, "", List.of(d1));
        long u2 = createUser("成员二", ext2, "", List.of(d1));
        long outsider = createUser("外部门", unique("out"), "", List.of(d2));

        // 1. 首次添加 u1，签发上下文
        mvc.perform(put(PATH_ADMIN_ROLE_MEMBERS, roleId).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(u1)))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ADMIN_ROLE_MEMBERS, roleId).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(u1));

        String u1CtxBody = mvc.perform(post(PATH_V1_CONTEXTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ContextIssueDTO(ext1, d1, null, true))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String u1Token = parseJson(u1CtxBody).get("contextToken").asText();

        // 2. 替换成员：移除 u1，新增 u2 -> u1 在该域的上下文立即失效
        mvc.perform(put(PATH_ADMIN_ROLE_MEMBERS, roleId).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(u2)))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app.apiKey()).header("X-Context-Token", u1Token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_CONTEXT_INVALID));
        mvc.perform(get(PATH_ADMIN_ROLE_MEMBERS, roleId).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(u2));

        // 3. 边界：清空全部成员
        mvc.perform(put(PATH_ADMIN_ROLE_MEMBERS, roleId).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of()))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ADMIN_ROLE_MEMBERS, roleId).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        // 4. 非本部门成员 403；不存在用户 404
        mvc.perform(put(PATH_ADMIN_ROLE_MEMBERS, roleId).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(outsider)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        mvc.perform(put(PATH_ADMIN_ROLE_MEMBERS, roleId).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(MISSING_USER_ID)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));

        // 5. 仅有 domain.configure 缺少 domain.assign 的自定义管理员不能维护角色成员 -> 403
        String cfgRoleCode = ("CFG_" + tag).toUpperCase();
        mvc.perform(post(PATH_ADMIN_CUSTOM_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(cfgRoleCode, "仅配置", "", List.of("domain.view", "domain.configure")))))
                .andExpect(status().isOk());
        var cfgAdmin = createAdminAndLogin("cfgonly", cfgRoleCode, AdminPrincipal.MANAGER, null, null);
        mvc.perform(put(PATH_ADMIN_ROLE_MEMBERS, roleId).param("departmentId", String.valueOf(d1))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", cfgAdmin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(u1)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
    }

    @Test
    @DisplayName("R6-04 同隔离域重复角色编码返回 409 DUPLICATE")
    void duplicateRoleCodeInSameDomainReturns409() throws Exception {
        long dept = createDepartment("重码部", unique("dr"));
        var app = createApplication("DupRoleApp", unique("dra"));
        createRole(dept, app.id(), "原角色", VALID_ROLE_CODE, "");

        mvc.perform(post(PATH_ADMIN_ROLES).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RoleDTO("冲突角色", VALID_ROLE_CODE, ""))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_DUPLICATE));
    }

    @Test
    @DisplayName("R6-05 同隔离域重复权限编码返回 409 DUPLICATE")
    void duplicatePermissionCodeInSameDomainReturns409() throws Exception {
        long dept = createDepartment("重权部", unique("dp"));
        var app = createApplication("DupPermApp", unique("dpa"));
        createPermission(dept, app.id(), VALID_PERM_NAME, VALID_PERM_CODE, VALID_RESOURCE, VALID_ACTION);

        mvc.perform(post(PATH_ADMIN_PERMS).param("departmentId", String.valueOf(dept))
                        .param("applicationId", String.valueOf(app.id())).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PermissionDTO("冲突权限", VALID_PERM_CODE, VALID_RESOURCE, VALID_ACTION))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_DUPLICATE));
    }
}
