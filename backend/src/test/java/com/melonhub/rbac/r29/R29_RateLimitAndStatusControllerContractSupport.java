package com.melonhub.rbac.r29;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.admin.dto.AdminRoleCreateDTO;
import com.melonhub.rbac.api.application.dto.RateLimitUpdateDTO;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * R29 软件每秒限额配置、公开健康检查与系统状态看板 —— 真实 MySQL + Redis Controller 层双策略全量测试。
 */
abstract class R29_RateLimitAndStatusControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、软件限额边界 0/500/100000/null、非法 -1/100001 与预期错误码）
    // =========================================================================
    private static final String PATH_RATE_LIMIT = "/api/admin/applications/{id}/rate-limit";
    private static final String PATH_V1_DEPTS = "/api/v1/departments";
    private static final String PATH_HEALTH = "/api/health";
    private static final String PATH_STATUS = "/api/admin/system/status";
    private static final String PATH_ADMIN_ROLES = "/api/admin/admin-roles";

    private static final long MISSING_APP_ID = 9_999_999L;
    private static final int VALID_LIMIT = 500;
    private static final int STRICT_LIMIT_1_PER_SEC = 1;
    private static final int BURST_ATTEMPTS = 3;
    private static final int BOUNDARY_UNLIMITED = 0;
    private static final int BOUNDARY_MAX_LIMIT = 100_000;
    private static final int INVALID_NEGATIVE = -1;
    private static final int INVALID_OVER_MAX = 100_001;

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_RATE_LIMITED = "RATE_LIMITED";

    @Test
    @DisplayName("R29-01 软件限额边界(0/500/100000/null)、非法(-1/100001)400、不存在404、审计员403，健康检查与系统状态看板")
    void rateLimitConfigHealthAndSystemStatus() throws Exception {
        var app = createApplication("LimitApp", unique("la"));

        // 1. 合法与边界限额设置（500 / 0 不限 / 100000 上限 / null 恢复默认）
        for (Integer limit : new Integer[]{VALID_LIMIT, BOUNDARY_UNLIMITED, BOUNDARY_MAX_LIMIT, null}) {
            mvc.perform(put(PATH_RATE_LIMIT, app.id()).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(new RateLimitUpdateDTO(limit))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));
        }

        // 2. 非法值 -1 / 100001 -> 400；不存在软件 -> 404；审计员 -> 403
        for (int bad : List.of(INVALID_NEGATIVE, INVALID_OVER_MAX)) {
            mvc.perform(put(PATH_RATE_LIMIT, app.id()).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(new RateLimitUpdateDTO(bad))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }
        mvc.perform(put(PATH_RATE_LIMIT, MISSING_APP_ID).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new RateLimitUpdateDTO(VALID_LIMIT))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));
        var auditor = createAuditor();
        mvc.perform(put(PATH_RATE_LIMIT, app.id()).header("Authorization", auditor.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new RateLimitUpdateDTO(VALID_LIMIT))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));

        // 3. 公开 /api/health 与 /api/admin/system/status（含缺少 system.view 403）
        mvc.perform(get(PATH_HEALTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.model").value("RBAC1"));
        mvc.perform(get(PATH_STATUS).header("Authorization", auditor.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.counts.applications").isNumber())
                .andExpect(jsonPath("$.audit.total").isNumber());

        String noSysRole = ("NOSYS_" + tag).toUpperCase();
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(noSysRole, "无看板", "", List.of("org.view")))))
                .andExpect(status().isOk());
        var noSysAdmin = createAdminAndLogin("nosys", noSysRole, AdminPrincipal.MANAGER, null, null);
        mvc.perform(get(PATH_STATUS).header("Authorization", noSysAdmin.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
    }

    @Test
    @DisplayName("R29-02 真实 ApiGuard 限流触发 429 RATE_LIMITED 并返回 Retry-After，设 0 不限流时突发全部 200")
    void apiGuardEnforcesApplicationRateLimitWith429AndRetryAfter() throws Exception {
        var strictApp = createApplication("StrictLimitApp", unique("sla"));
        mvc.perform(put(PATH_RATE_LIMIT, strictApp.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RateLimitUpdateDTO(STRICT_LIMIT_1_PER_SEC))))
                .andExpect(status().isOk());

        int okCount = 0;
        int limitedCount = 0;
        for (int i = 0; i < BURST_ATTEMPTS; i++) {
            MvcResult res = mvc.perform(get(PATH_V1_DEPTS).header("X-API-Key", strictApp.apiKey())).andReturn();
            int sc = res.getResponse().getStatus();
            if (sc == 200) {
                okCount++;
            } else if (sc == 429) {
                limitedCount++;
                assertThat(res.getResponse().getHeader("Retry-After")).isEqualTo("1");
                assertThat(parseJson(res.getResponse().getContentAsString()).get("code").asText())
                        .isEqualTo(ERR_RATE_LIMITED);
            } else {
                throw new AssertionError("Unexpected HTTP status during rate-limit burst: " + sc);
            }
        }
        assertThat(okCount).isGreaterThanOrEqualTo(1);
        assertThat(limitedCount).isGreaterThanOrEqualTo(1);

        var unlimitedApp = createApplication("UnlimitedApp", unique("ula"));
        mvc.perform(put(PATH_RATE_LIMIT, unlimitedApp.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RateLimitUpdateDTO(BOUNDARY_UNLIMITED))))
                .andExpect(status().isOk());
        for (int i = 0; i < BURST_ATTEMPTS; i++) {
            mvc.perform(get(PATH_V1_DEPTS).header("X-API-Key", unlimitedApp.apiKey()))
                    .andExpect(status().isOk());
        }
    }
}
