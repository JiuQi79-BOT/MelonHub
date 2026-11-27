package com.melonhub.rbac.r15;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.webhook.dto.WebhookDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R15 Webhook 订阅管理、events 数组契约、边界密钥/非法值校验、测试推送入队与投递分页 —— 真实 MySQL Controller 层双策略测试。
 */
abstract class R15_WebhookControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、合法/边界/非法 URL、密钥长度 15/16/100、事件类型与预期错误码）
    // =========================================================================
    private static final String PATH_APP_HOOKS = "/api/admin/applications/{id}/webhooks";
    private static final String PATH_HOOK_BY_ID = "/api/admin/webhooks/{id}";
    private static final String PATH_HOOK_DELIVERIES = "/api/admin/webhooks/{id}/deliveries";
    private static final String PATH_HOOK_TEST = "/api/admin/webhooks/{id}/test";

    private static final long MISSING_HOOK_ID = 9_999_999L;
    private static final String VALID_NAME = "权限变更通知";
    private static final String VALID_URL = "https://biz.example.com/webhooks/rbac";
    private static final String BOUNDARY_SECRET_16 = "s".repeat(16);
    private static final String BOUNDARY_SECRET_100 = "s".repeat(100);
    private static final String INVALID_SECRET_15 = "s".repeat(15);
    private static final String INVALID_SECRET_LEADING_SPACE = " " + "s".repeat(16);
    private static final String INVALID_URL_FTP = "ftp://biz.example.com/hook";
    private static final String INVALID_UNKNOWN_EVENT = "evil.unknown.event";

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_CONFLICT = "CONFLICT";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";

    @Test
    @DisplayName("R15-01 Webhook 增/查(数组契约)/改/删、边界密钥 16/100、非法 DTO/事件 400、停用测试 409、启用测试入队与越权 403")
    void webhookCrudTestDeliveriesValidationAndScope() throws Exception {
        var app1 = createApplication("HookApp1", unique("ha1"));
        var app2 = createApplication("HookApp2", unique("ha2"));

        // 1. 新建：自动生成密钥 + 边界 16/100 字符自定义密钥 + role.inheritance.changed
        long hook1Id = parseJson(mvc.perform(post(PATH_APP_HOOKS, app1.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new WebhookDTO(VALID_NAME, VALID_URL, null, List.of("*"), true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post(PATH_APP_HOOKS, app1.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new WebhookDTO("边界16", VALID_URL, BOUNDARY_SECRET_16, List.of("role.enabled"), true))))
                .andExpect(status().isOk());
        long disabledHookId = parseJson(mvc.perform(post(PATH_APP_HOOKS, app1.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new WebhookDTO("停用钩子", VALID_URL, BOUNDARY_SECRET_100, List.of("role.inheritance.changed"), false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        // 2. 列表验证 events 数组契约
        mvc.perform(get(PATH_APP_HOOKS, app1.id()).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].events").isArray());

        // 3. 非法 DTO & 未知事件类型 -> 400
        for (WebhookDTO bad : List.of(
                new WebhookDTO(" ", VALID_URL, null, List.of("*"), true),
                new WebhookDTO(VALID_NAME, INVALID_URL_FTP, null, List.of("*"), true),
                new WebhookDTO(VALID_NAME, VALID_URL, INVALID_SECRET_15, List.of("*"), true),
                new WebhookDTO(VALID_NAME, VALID_URL, INVALID_SECRET_LEADING_SPACE, List.of("*"), true),
                new WebhookDTO(VALID_NAME, VALID_URL, null, List.of(), true),
                new WebhookDTO(VALID_NAME, VALID_URL, null, List.of(INVALID_UNKNOWN_EVENT), true)
        )) {
            mvc.perform(post(PATH_APP_HOOKS, app1.id()).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }

        // 4. 修改 + 测试推送（启用成功入队可查 deliveries；停用返回 409 CONFLICT）
        mvc.perform(put(PATH_HOOK_BY_ID, hook1Id).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new WebhookDTO("已更新", VALID_URL, BOUNDARY_SECRET_16, List.of("role.enabled", "user.roles.changed"), true))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_HOOK_TEST, hook1Id).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_HOOK_DELIVERIES, hook1Id).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].eventType").value("webhook.test"));
        mvc.perform(post(PATH_HOOK_TEST, disabledHookId).header("Authorization", superBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));

        // 5. 范围越权 403（软件管理员跨软件、审计员写）+ 删除与不存在 404
        var app2Admin = createAppAdmin(List.of(app2.id()));
        mvc.perform(post(PATH_HOOK_TEST, hook1Id).header("Authorization", app2Admin.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        var auditor = createAuditor();
        mvc.perform(post(PATH_APP_HOOKS, app1.id()).header("Authorization", auditor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new WebhookDTO(VALID_NAME, VALID_URL, null, List.of("*"), true))))
                .andExpect(status().isForbidden());

        mvc.perform(delete(PATH_HOOK_BY_ID, hook1Id).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(delete(PATH_HOOK_BY_ID, MISSING_HOOK_ID).header("Authorization", superBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));
    }
}
