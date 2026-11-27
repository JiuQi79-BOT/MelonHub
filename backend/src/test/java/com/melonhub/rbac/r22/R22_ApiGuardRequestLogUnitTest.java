package com.melonhub.rbac.r22;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.infrastructure.security.ApiGuard;
import com.melonhub.rbac.infrastructure.security.ApplicationRateLimits;
import com.melonhub.rbac.infrastructure.security.GlobalRateLimiter;
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/** 核对管理菜单请求的完成日志与脱敏边界。 对应需求：R22（见 doc/requirements.md）。 */
@ExtendWith(OutputCaptureExtension.class)
class R22_ApiGuardRequestLogUnitTest {

    // =========================================================================
    // 统一测试参数区（请求路径、路由模板、追踪号、脱敏凭据与敏感查询参数）
    // =========================================================================
    private static final int LOGIN_LIMIT_PER_MIN = 10;
    private static final String HTTP_METHOD = "GET";
    private static final String REQUEST_URI = "/api/admin/accounts/42";
    private static final String ROUTE_TEMPLATE = "/api/admin/accounts/{id}";
    private static final String REQUEST_ID = "menu-test-1234";
    private static final String SENSITIVE_TOKEN = "private-test-token";
    private static final String SENSITIVE_QUERY = "private-search-value";
    private static final int RESPONSE_STATUS_FORBIDDEN = 403;

    /** 记录实际完成的管理请求，日志使用路由模板而非用户输入。 */
    @Test
    @DisplayName("R22 / MH-06 管理菜单请求记录同一请求编号且省略凭据和用户输入")
    void recordsMenuRequestWithoutCredentialsOrUserInput(CapturedOutput output) throws Exception {
        ApiGuard guard = new ApiGuard(mock(AuthUseCase.class), new ObjectMapper(),
                mock(GlobalRateLimiter.class), mock(ApplicationRateLimits.class), LOGIN_LIMIT_PER_MIN);
        MockHttpServletRequest request = new MockHttpServletRequest(HTTP_METHOD, REQUEST_URI);
        request.addHeader("X-Request-Id", REQUEST_ID);
        request.addHeader("Authorization", "Bearer " + SENSITIVE_TOKEN);
        request.setQueryString("keyword=" + SENSITIVE_QUERY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, (incoming, outgoing) -> {
            incoming.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, ROUTE_TEMPLATE);
            response.setStatus(RESPONSE_STATUS_FORBIDDEN);
        });

        assertThat(response.getHeader("X-Request-Id")).isEqualTo(REQUEST_ID);
        assertThat(output.getOut())
                .contains("requestId=" + REQUEST_ID + " method=" + HTTP_METHOD + " route=" + ROUTE_TEMPLATE
                        + " status=" + RESPONSE_STATUS_FORBIDDEN + " durationMs=")
                .doesNotContain("example-user", SENSITIVE_TOKEN, SENSITIVE_QUERY);
    }
}
