package com.melonhub.rbac.r22;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.shared.RealDbControllerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R22 ApiGuard 规范路径防绕过、安全响应头、X-Request-Id 与 HTTP 错误契约 —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R22_ApiGuardControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（合法/非规范路径、追踪号、安全响应头与预期错误码）
    // =========================================================================
    private static final String PATH_HEALTH = "/api/health";
    private static final String PATH_ADMIN_DEPTS = "/api/admin/departments";
    private static final String PATH_UNKNOWN_API = "/api/unknown/resource";
    private static final String PATH_MATRIX_BYPASS = "/api/admin/departments;jsessionid=1";
    private static final String PATH_PERCENT_BYPASS = "/api/admin/%64epartments";
    private static final String PATH_DOUBLE_SLASH = "/api//health";
    private static final String PATH_DOT_TRAVERSAL = "/api/admin/../health";

    private static final String VALID_REQUEST_ID = "req-trace-realdb-01";
    private static final String INVALID_REQUEST_ID = "bad req id!";

    private static final String ERR_INVALID_PATH = "INVALID_PATH";
    private static final String ERR_UNAUTHORIZED = "UNAUTHORIZED";
    private static final String ERR_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    private static final String ERR_UNSUPPORTED_MEDIA = "UNSUPPORTED_MEDIA_TYPE";
    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";

    @Test
    @DisplayName("R22-01 公开健康检查、5项安全响应头、X-Request-Id 透传与过滤、四类非规范路径 400 及 401/405/415/400 契约")
    void canonicalPathDefenseSecurityHeadersAndErrorContracts() throws Exception {
        // 1. 公开 /api/health 无需认证，透传合法 X-Request-Id 并返回 5 项安全响应头
        mvc.perform(get(PATH_HEALTH).header("X-Request-Id", VALID_REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", VALID_REQUEST_ID))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("Content-Security-Policy"));

        // 非法 X-Request-Id 不透传，改为服务端生成
        mvc.perform(get(PATH_HEALTH).header("X-Request-Id", INVALID_REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", notNullValue()))
                .andExpect(header().string("X-Request-Id", not(INVALID_REQUEST_ID)));

        // 2. 四类非规范绕过路径 -> 400 INVALID_PATH
        for (String badPath : new String[]{PATH_MATRIX_BYPASS, PATH_PERCENT_BYPASS, PATH_DOUBLE_SLASH, PATH_DOT_TRAVERSAL}) {
            mvc.perform(get(PATH_HEALTH).with(req -> {
                        req.setRequestURI(badPath);
                        return req;
                    }).header("Authorization", superBearer))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_PATH))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
        }

        // 3. 未知 /api/** 默认拒绝 401
        mvc.perform(get(PATH_UNKNOWN_API))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ERR_UNAUTHORIZED));

        // 4. 405 方法不支持、415 媒体类型不支持、400 畸形 JSON
        mvc.perform(delete(PATH_ADMIN_DEPTS).header("Authorization", superBearer))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(ERR_METHOD_NOT_ALLOWED));
        mvc.perform(post(PATH_ADMIN_DEPTS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_XML).content("<dept/>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ERR_UNSUPPORTED_MEDIA));
        mvc.perform(post(PATH_ADMIN_DEPTS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{broken-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
    }
}
