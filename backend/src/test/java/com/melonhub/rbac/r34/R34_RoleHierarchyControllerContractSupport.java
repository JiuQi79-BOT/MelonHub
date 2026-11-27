package com.melonhub.rbac.r34;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.context.dto.ContextIssueDTO;
import com.melonhub.rbac.api.domainrbac.dto.SimulationDTO;
import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.api.shared.dto.IdsDTO;
import com.melonhub.rbac.api.webhook.dto.WebhookDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

/**
 * R34 RBAC1 角色层级继承 —— 真实 MySQL Controller 层全量正常/多父菱形/清空/自继承/二元环/三元传递环/跨域/停用阻断/实时展开双策略测试。
 */
abstract class R34_RoleHierarchyControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、三层权限编码与预期错误码）
    // =========================================================================
    private static final String PATH_ADMIN_ROLE_PARENTS = "/api/admin/roles/{id}/parents";
    private static final String PATH_V1_ROLE_PARENTS = "/api/v1/departments/{dept}/roles/{id}/parents";
    private static final String PATH_ROLE_PERMS = "/api/admin/roles/{id}/permissions";
    private static final String PATH_ROLE_STATUS = "/api/admin/roles/{id}/status";
    private static final String PATH_USER_ROLES = "/api/admin/users/{id}/roles";
    private static final String PATH_PERMS_WHO = "/api/admin/permissions/who";
    private static final String PATH_SIMULATE = "/api/admin/simulate";
    private static final String PATH_APP_HOOKS = "/api/admin/applications/{id}/webhooks";
    private static final String PATH_V1_CONTEXTS = "/api/v1/contexts";
    private static final String PATH_V1_EFFECTIVE = "/api/v1/contexts/effective";

    private static final String PERM_CHILD = "hier:child";
    private static final String PERM_PARENT = "hier:parent";
    private static final String PERM_GRANDPARENT = "hier:grandparent";
    private static final String HOOK_URL = "https://biz.example.com/hier-hook";
    private static final String EVENT_INHERITANCE_CHANGED = "role.inheritance.changed";

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_CONFLICT = "CONFLICT";

    @Test
    @DisplayName("R34-01 角色继承拓扑（单父/菱形多父/清空/自继承403/二元环与三元传递环409/跨域403/非法DTO400）与三层权限实时展开及停用阻断")
    void roleHierarchyTopologyCycleDefenseTransitiveExpansionAndDisabledParentBlock() throws Exception {
        long d1 = createDepartment("层级一部", unique("hd1"));
        long d2 = createDepartment("层级二部", unique("hd2"));
        var app = createApplication("HierApp", unique("ha"));
        String ext = unique("hu");
        long userId = createUser("层级员", ext, "", List.of(d1));

        long pChild = createPermission(d1, app.id(), "子权", PERM_CHILD, "h", "child");
        long pParent = createPermission(d1, app.id(), "父权", PERM_PARENT, "h", "parent");
        long pGrand = createPermission(d1, app.id(), "祖权", PERM_GRANDPARENT, "h", "grand");

        long rChild = createRole(d1, app.id(), "子角色", "child", "");
        long rParent = createRole(d1, app.id(), "父角色", "parent", "");
        long rGrand = createRole(d1, app.id(), "祖角色", "grandparent", "");
        long rForeign = createRole(d2, app.id(), "异域角色", "foreign", "");

        mvc.perform(put(PATH_ROLE_PERMS, rChild).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(pChild)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_ROLE_PERMS, rParent).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(pParent)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_ROLE_PERMS, rGrand).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(pGrand)))))
                .andExpect(status().isOk());

        // 订阅 role.inheritance.changed Webhook 并构建三层继承链：child -> parent -> grandparent（管理端与业务端）
        mvc.perform(post(PATH_APP_HOOKS, app.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new WebhookDTO("层级事件", HOOK_URL, null, List.of(EVENT_INHERITANCE_CHANGED), true))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_ADMIN_ROLE_PARENTS, rParent).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(rGrand)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_V1_ROLE_PARENTS, d1, rChild).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(rParent)))))
                .andExpect(status().isOk());
        Integer hookDeliveries = db.queryForObject(
                "SELECT COUNT(*) FROM webhook_deliveries d JOIN webhooks w ON w.id = d.webhook_id WHERE w.application_id = ? AND d.event_type = ?",
                Integer.class, app.id(), EVENT_INHERITANCE_CHANGED);
        assertThat(hookDeliveries).isGreaterThanOrEqualTo(2);

        // 2. 非法拓扑拒绝：自继承 403、二元环(parent->child) 409、三元传递环(grand->child) 409、跨域父角色 403、非法 DTO 400、审计员 403
        mvc.perform(put(PATH_ADMIN_ROLE_PARENTS, rChild).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(rChild)))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        mvc.perform(put(PATH_ADMIN_ROLE_PARENTS, rParent).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(rChild)))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(ERR_CONFLICT));
        mvc.perform(put(PATH_ADMIN_ROLE_PARENTS, rGrand).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(rChild)))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(ERR_CONFLICT));
        mvc.perform(put(PATH_ADMIN_ROLE_PARENTS, rChild).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(rForeign)))))
                .andExpect(status().isForbidden());
        mvc.perform(put(PATH_ADMIN_ROLE_PARENTS, rChild).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(0L)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        var auditor = createAuditor();
        mvc.perform(put(PATH_ADMIN_ROLE_PARENTS, rChild).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", auditor.bearer()).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(rParent)))))
                .andExpect(status().isForbidden());

        // 3. 仅分配 rChild 给用户 -> 签发上下文后自动递归展开返回 child/parent/grandparent 全部三层权限
        mvc.perform(put(PATH_USER_ROLES, userId).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new UserRolesDTO(List.of(rChild), null, null))))
                .andExpect(status().isOk());
        String token = parseJson(mvc.perform(post(PATH_V1_CONTEXTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ContextIssueDTO(ext, d1, null, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(PERM_CHILD, PERM_PARENT, PERM_GRANDPARENT)))
                .andReturn().getResponse().getContentAsString()).get("contextToken").asText();

        // 3.5 权限反查 /permissions/who 必须覆盖通过继承获得祖权限的用户，且 /simulate 中 grantedBy 归因回直接分配角色 rChild
        mvc.perform(get(PATH_PERMS_WHO).param("applicationId", String.valueOf(app.id()))
                        .param("code", PERM_GRANDPARENT).param("departmentId", String.valueOf(d1))
                        .header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.holders[0].userId").value(userId))
                .andExpect(jsonPath("$.holders[0].roles[0].id").value(rChild));
        mvc.perform(post(PATH_SIMULATE).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SimulationDTO(userId, d1, app.id(), null, List.of(PERM_GRANDPARENT)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.permissions[?(@.code=='" + PERM_GRANDPARENT + "')].grantedBy[0]", hasItem((int) rChild)));

        // 3.6 同时直授 rChild 与 rParent 并传入非升序 activeRoleIds -> Structured 与 DDD 的 grantedBy 顺序严格保持输入序
        mvc.perform(put(PATH_USER_ROLES, userId).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(rChild, rParent), null, null))))
                .andExpect(status().isOk());
        long higherRole = Math.max(rChild, rParent);
        long lowerRole = Math.min(rChild, rParent);
        mvc.perform(post(PATH_SIMULATE).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SimulationDTO(userId, d1, app.id(), List.of(higherRole, lowerRole), List.of(PERM_GRANDPARENT)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[?(@.code=='" + PERM_GRANDPARENT + "')].grantedBy[0]", hasItem((int) higherRole)))
                .andExpect(jsonPath("$.permissions[?(@.code=='" + PERM_GRANDPARENT + "')].grantedBy[1]", hasItem((int) lowerRole)));
        // 恢复仅直授 rChild，继续后续停用中间层与多父测试
        mvc.perform(put(PATH_USER_ROLES, userId).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(rChild), null, null))))
                .andExpect(status().isOk());
        token = parseJson(mvc.perform(post(PATH_V1_CONTEXTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ContextIssueDTO(ext, d1, null, true))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("contextToken").asText();

        // 4. 停用中间层 rParent -> 立即阻断 rParent 与经其传递的 rGrand 权限，仅保留 rChild 自身权限
        mvc.perform(patch(PATH_ROLE_STATUS, rParent).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(false))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app.apiKey()).header("X-Context-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", hasItem(PERM_CHILD)))
                .andExpect(jsonPath("$.permissions", not(hasItem(PERM_PARENT))))
                .andExpect(jsonPath("$.permissions", not(hasItem(PERM_GRANDPARENT))));

        // 5. 菱形多父继承（child 同时直接继承 rParent 与 rGrand）与清空父角色 []
        mvc.perform(put(PATH_ADMIN_ROLE_PARENTS, rChild).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(rParent, rGrand)))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app.apiKey()).header("X-Context-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(PERM_CHILD, PERM_GRANDPARENT)));
        mvc.perform(put(PATH_ADMIN_ROLE_PARENTS, rChild).param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of()))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app.apiKey()).header("X-Context-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0]").value(PERM_CHILD));

        // 6. V17 数据库复合外键物理兜底：直接跨隔离域写入 role_inheritances 触发外键约束异常
        assertThatThrownBy(() -> db.update(
                "INSERT INTO role_inheritances(child_role_id, parent_role_id, department_id, application_id) VALUES (?, ?, ?, ?)",
                rChild, rForeign, d1, app.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
