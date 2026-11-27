package com.melonhub.rbac.r14;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.context.dto.ContextIssueDTO;
import com.melonhub.rbac.api.context.dto.PermissionCheckDTO;
import com.melonhub.rbac.api.domainrbac.dto.UserRolesDTO;
import com.melonhub.rbac.api.shared.dto.IdsDTO;
import com.melonhub.rbac.api.webhook.dto.WebhookDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R14 角色分配到期即时生效（不失效上下文、即时不计入到期角色）与定时清理删行/审计/发 Webhook —— 真实 MySQL Controller 层双策略测试。
 */
abstract class R14_AssignmentExpiryControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、保留与到期权限码、Webhook 地址与预期事件类型）
    // =========================================================================
    private static final String PATH_ROLE_PERMS = "/api/admin/roles/{id}/permissions";
    private static final String PATH_USER_ROLES = "/api/admin/users/{id}/roles";
    private static final String PATH_APP_HOOKS = "/api/admin/applications/{id}/webhooks";
    private static final String PATH_V1_CONTEXTS = "/api/v1/contexts";
    private static final String PATH_V1_EFFECTIVE = "/api/v1/contexts/effective";
    private static final String PATH_V1_CHECK = "/api/v1/contexts/check";

    private static final String PERM_KEEP = "doc:keep";
    private static final String PERM_EXPIRE = "doc:temp";
    private static final String HOOK_URL = "https://biz.example.com/expiry-hook";
    private static final String EVENT_EXPIRED = "user.roles.expired";
    private static final String AUDIT_ACTION_EXPIRED = "USER_ROLES_EXPIRED";

    @Test
    @DisplayName("R14-01 角色分配到期后无需清理任务立即在 /effective 与 /check 失效且上下文保持有效，清理后删行/写审计/发事件")
    void expiredAssignmentDropsImmediatelyAndCleanupEmitsAuditAndWebhook() throws Exception {
        long dept = createDepartment("到期部", unique("expd"));
        var app = createApplication("ExpApp", unique("exa"));
        String ext = unique("expu");
        long userId = createUser("临时员", ext, "", List.of(dept));
        long pKeep = createPermission(dept, app.id(), "常驻", PERM_KEEP, "doc", "keep");
        long pTemp = createPermission(dept, app.id(), "临时", PERM_EXPIRE, "doc", "temp");
        long rKeep = createRole(dept, app.id(), "常驻角", "keep-r", "");
        long rTemp = createRole(dept, app.id(), "临时角", "temp-r", "");
        mvc.perform(put(PATH_ROLE_PERMS, rKeep).param("departmentId", String.valueOf(dept)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(pKeep)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_ROLE_PERMS, rTemp).param("departmentId", String.valueOf(dept)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON).content(json(new IdsDTO(List.of(pTemp)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_USER_ROLES, userId).param("departmentId", String.valueOf(dept)).param("applicationId", String.valueOf(app.id()))
                        .header("Authorization", superBearer).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserRolesDTO(List.of(rKeep, rTemp), null, null))))
                .andExpect(status().isOk());

        // 注册订阅 user.roles.expired 的 Webhook
        mvc.perform(post(PATH_APP_HOOKS, app.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new WebhookDTO("到期通知", HOOK_URL, null, List.of(EVENT_EXPIRED), true))))
                .andExpect(status().isOk());

        // 签发上下文时两项权限均有效
        String token = parseJson(mvc.perform(post(PATH_V1_CONTEXTS).header("X-API-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ContextIssueDTO(ext, dept, null, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", hasItem(PERM_EXPIRE)))
                .andReturn().getResponse().getContentAsString()).get("contextToken").asText();

        // 1. 模拟 rTemp 已到期（未运行定时清理任务）-> 上下文依然 200 有效，但立刻剔除 rTemp 及 doc:temp，保留 doc:keep
        db.update("UPDATE user_roles SET expires_at = TIMESTAMPADD(SECOND, -5, CURRENT_TIMESTAMP) WHERE user_id = ? AND role_id = ?",
                userId, rTemp);
        mvc.perform(get(PATH_V1_EFFECTIVE).header("X-API-Key", app.apiKey()).header("X-Context-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", hasItem(PERM_KEEP)))
                .andExpect(jsonPath("$.permissions", not(hasItem(PERM_EXPIRE))));
        mvc.perform(post(PATH_V1_CHECK).header("X-API-Key", app.apiKey()).header("X-Context-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PermissionCheckDTO(List.of(PERM_KEEP, PERM_EXPIRE)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.decisions['" + PERM_KEEP + "']").value(true))
                .andExpect(jsonPath("$.decisions['" + PERM_EXPIRE + "']").value(false));

        // 2. 执行到期清理 -> 删除过期行、记录 system 审计事件、入队 user.roles.expired Webhook；重复清理幂等不重发
        int expiredCount = governanceUseCase.expireAssignments(100);
        assertThat(expiredCount).isGreaterThanOrEqualTo(1);
        assertThat(governanceUseCase.expireAssignments(100)).isZero();
        Integer remaining = db.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role_id = ?", Integer.class, userId, rTemp);
        assertThat(remaining).isZero();
        Integer auditCount = db.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE actor = 'system' AND action = ? AND target = ?",
                Integer.class, AUDIT_ACTION_EXPIRED, ext);
        assertThat(auditCount).isEqualTo(1);
        Integer deliveryCount = db.queryForObject(
                "SELECT COUNT(*) FROM webhook_deliveries d JOIN webhooks w ON w.id = d.webhook_id WHERE w.application_id = ? AND d.event_type = ?",
                Integer.class, app.id(), EVENT_EXPIRED);
        assertThat(deliveryCount).isEqualTo(1);
    }
}
