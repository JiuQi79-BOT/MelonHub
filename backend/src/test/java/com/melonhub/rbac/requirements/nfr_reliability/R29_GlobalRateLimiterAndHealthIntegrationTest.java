package com.melonhub.rbac.requirements.nfr_reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.requirements.support.GovernanceIntegrationSupport;
import com.melonhub.rbac.security.Credentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 对应需求：R29（见 doc/requirements.md）。 */
class R29_GlobalRateLimiterAndHealthIntegrationTest extends GovernanceIntegrationSupport {

    @DisplayName("R29 跨实例全局限流经 Redis 共享同一时间窗口")
    @Test void globalRateLimiterSharesOneRedisWindow() {
        assumeFalse("DISABLED".equals(cacheHealth()), "cache disabled");
        String id = Credentials.token().substring(0, 8);
        assertThat(globalRateLimiter.tryAcquire("test", id, 2, 60)).isTrue();
        assertThat(globalRateLimiter.tryAcquire("test", id, 2, 60)).isTrue();
        assertThat(globalRateLimiter.tryAcquire("test", id, 2, 60)).isFalse();
    }

    @DisplayName("R29 健康检查接口准确回报数据库与缓存状态")
    @Test void healthReportsDatabaseAndCache() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP")).andExpect(jsonPath("$.cache").exists()).andExpect(jsonPath("$.model").value("RBAC1"));
    }
}
