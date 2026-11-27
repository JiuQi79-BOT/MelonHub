package com.melonhub.rbac.r29;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.shared.GovernanceIntegrationSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 跨实例 Redis 固定窗口全局限流与健康检查专项测试。对应需求：R29（见 doc/requirements.md）。 */
class R29_GlobalRateLimiterAndHealthIntegrationTest extends GovernanceIntegrationSupport {
    // 统一测试参数区
    private static final String RATE_BUCKET = "test";
    private static final int RATE_LIMIT = 2;
    private static final int WINDOW_SECONDS = 60;
    private static final String HEALTH_ENDPOINT = "/api/health";
    private static final String STATUS_UP = "UP";
    private static final String MODEL_RBAC1 = "RBAC1";

    @DisplayName("R29 跨实例全局限流经 Redis 共享同一时间窗口")
    @Test void globalRateLimiterSharesOneRedisWindow() {
        assumeFalse("DISABLED".equals(cacheHealth()), "cache disabled");
        String id = Credentials.token().substring(0, 8);
        assertThat(globalRateLimiter.tryAcquire(RATE_BUCKET, id, RATE_LIMIT, WINDOW_SECONDS)).isTrue();
        assertThat(globalRateLimiter.tryAcquire(RATE_BUCKET, id, RATE_LIMIT, WINDOW_SECONDS)).isTrue();
        assertThat(globalRateLimiter.tryAcquire(RATE_BUCKET, id, RATE_LIMIT, WINDOW_SECONDS)).isFalse();
    }

    @DisplayName("R29 健康检查接口准确回报数据库与缓存状态")
    @Test void healthReportsDatabaseAndCache() throws Exception {
        mvc.perform(get(HEALTH_ENDPOINT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(STATUS_UP))
                .andExpect(jsonPath("$.database").value(STATUS_UP))
                .andExpect(jsonPath("$.cache").exists())
                .andExpect(jsonPath("$.model").value(MODEL_RBAC1));
    }
}
