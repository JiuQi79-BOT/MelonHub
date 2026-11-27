package com.melonhub.rbac.r04;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.application.dto.ApiKeyDTO;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R4 软件多 API Key 管理、上限(10个)、单独吊销、紧急轮换与凭据隔离 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R04_ApiKeyControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、密钥边界天数/名称、最大有效数 10 与预期错误码）
    // =========================================================================
    private static final String PATH_KEYS = "/api/admin/applications/{id}/keys";
    private static final String PATH_KEY_BY_ID = "/api/admin/applications/{id}/keys/{keyId}";
    private static final String PATH_ROTATE = "/api/admin/applications/{id}/rotate-key";
    private static final String PATH_V1_DEPTS = "/api/v1/departments";
    private static final String PATH_ADMIN_DEPTS = "/api/admin/departments";

    private static final long MISSING_APP_ID = 9_999_999L;
    private static final long MISSING_KEY_ID = 9_999_998L;
    private static final int MAX_ACTIVE_KEYS = 10;
    private static final int BOUNDARY_MIN_DAYS = 1;
    private static final int BOUNDARY_MAX_DAYS = 3650;
    private static final int INVALID_DAYS_ZERO = 0;
    private static final int INVALID_DAYS_NEG = -1;
    private static final int INVALID_DAYS_OVER = 3651;
    private static final String BOUNDARY_NAME_100 = "k".repeat(100);
    private static final String INVALID_NAME_101 = "k".repeat(101);

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_CONFLICT = "CONFLICT";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_UNAUTHORIZED = "UNAUTHORIZED";

    @Test
    @DisplayName("R4-01 多 API Key 创建（永久/边界 1 天与 3650 天/100 字符名）、上限 10 个 409、非法 DTO 400 与软件范围 403")
    void createListLimitValidationAndAppScope() throws Exception {
        var app1 = createApplication("KeyApp1", unique("ka1"));
        var app2 = createApplication("KeyApp2", unique("ka2"));
        var app1Admin = createAppAdmin(List.of(app1.id()));

        // 1. 列表：软件管理员可看本软件，看其他软件 403；不存在软件 404
        mvc.perform(get(PATH_KEYS, app1.id()).header("Authorization", app1Admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        mvc.perform(get(PATH_KEYS, app2.id()).header("Authorization", app1Admin.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));
        mvc.perform(get(PATH_KEYS, MISSING_APP_ID).header("Authorization", superBearer))
                .andExpect(status().isNotFound());

        // 2. 边界创建（永久 + 1天/100字名 + 3650天），加上默认密钥达到上限 10 个，第 11 个 409 CONFLICT
        mvc.perform(post(PATH_KEYS, app1.id()).header("Authorization", app1Admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ApiKeyDTO("永久密钥", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isEmpty());
        mvc.perform(post(PATH_KEYS, app1.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ApiKeyDTO(BOUNDARY_NAME_100, BOUNDARY_MIN_DAYS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
        mvc.perform(post(PATH_KEYS, app1.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ApiKeyDTO("十年密钥", BOUNDARY_MAX_DAYS))))
                .andExpect(status().isOk());
        for (int i = 5; i <= MAX_ACTIVE_KEYS; i++) {
            mvc.perform(post(PATH_KEYS, app1.id()).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(new ApiKeyDTO("key-" + i, 30))))
                    .andExpect(status().isOk());
        }
        mvc.perform(post(PATH_KEYS, app1.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ApiKeyDTO("overflow", 30))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));

        // 3. 非法 DTO -> 400
        for (ApiKeyDTO bad : List.of(
                new ApiKeyDTO(" ", 30),
                new ApiKeyDTO(INVALID_NAME_101, 30),
                new ApiKeyDTO("k", INVALID_DAYS_ZERO),
                new ApiKeyDTO("k", INVALID_DAYS_NEG),
                new ApiKeyDTO("k", INVALID_DAYS_OVER)
        )) {
            mvc.perform(post(PATH_KEYS, app1.id()).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }

        // 4. 审计员 403；软件管理员越软件 403
        var auditor = createAuditor();
        mvc.perform(post(PATH_KEYS, app1.id()).header("Authorization", auditor.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ApiKeyDTO("aud", 10))))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH_KEYS, app2.id()).header("Authorization", app1Admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ApiKeyDTO("cross", 10))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R4-02 单独吊销（立即可见401、重复吊销409）、紧急轮换（全部旧Key失效、新Key生效）与凭据严格分离")
    void revokeRotateAndCredentialSeparation() throws Exception {
        var app = createApplication("RotApp", unique("ra"));
        String secondBody = mvc.perform(post(PATH_KEYS, app.id()).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new ApiKeyDTO("second", 30))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long secondKeyId = parseJson(secondBody).get("id").asLong();
        String secondKey = parseJson(secondBody).get("apiKey").asText();

        // 两把密钥都可用
        mvc.perform(get(PATH_V1_DEPTS).header("X-API-Key", app.apiKey())).andExpect(status().isOk());
        mvc.perform(get(PATH_V1_DEPTS).header("X-API-Key", secondKey)).andExpect(status().isOk());

        // 1. 单独吊销 secondKey：secondKey 立即 401，原默认 Key 仍 200；重复吊销 409；不存在 404
        mvc.perform(delete(PATH_KEY_BY_ID, app.id(), secondKeyId).header("Authorization", superBearer))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_V1_DEPTS).header("X-API-Key", secondKey)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH_V1_DEPTS).header("X-API-Key", app.apiKey())).andExpect(status().isOk());
        mvc.perform(delete(PATH_KEY_BY_ID, app.id(), secondKeyId).header("Authorization", superBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ERR_CONFLICT));
        mvc.perform(delete(PATH_KEY_BY_ID, app.id(), MISSING_KEY_ID).header("Authorization", superBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));

        // 2. 紧急轮换：原 Key 立即 401，新 Key 立即 200；不存在软件 404
        String rotatedBody = mvc.perform(post(PATH_ROTATE, app.id()).header("Authorization", superBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String rotatedKey = parseJson(rotatedBody).get("apiKey").asText();
        mvc.perform(get(PATH_V1_DEPTS).header("X-API-Key", app.apiKey())).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH_V1_DEPTS).header("X-API-Key", rotatedKey)).andExpect(status().isOk());
        // 过期密钥立即 401
        db.update("UPDATE application_keys SET expires_at = ? WHERE application_id = ? AND revoked_at IS NULL",
                Timestamp.from(Instant.now().minus(5, ChronoUnit.SECONDS)), app.id());
        mvc.perform(get(PATH_V1_DEPTS).header("X-API-Key", rotatedKey)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH_ROTATE, MISSING_APP_ID).header("Authorization", superBearer))
                .andExpect(status().isNotFound());

        // 3. 凭据严格隔离：控制台 Bearer 不能调 /api/v1，业务 API Key 不能调 /api/admin
        mvc.perform(get(PATH_V1_DEPTS).header("Authorization", superBearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_UNAUTHORIZED));
        mvc.perform(get(PATH_ADMIN_DEPTS).header("X-API-Key", rotatedKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_UNAUTHORIZED));
    }
}
