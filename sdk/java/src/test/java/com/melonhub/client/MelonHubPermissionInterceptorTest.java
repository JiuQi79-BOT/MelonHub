package com.melonhub.client;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class MelonHubPermissionInterceptorTest {

    /** hasPermission 由测试内桩实现，不依赖真实 HTTP。 */
    private static MelonHubAuthorizer stubAuthorizer(boolean allowed) {
        return new MelonHubAuthorizer(new MelonHubClient("http://127.0.0.1:1", "mh_test")) {
            @Override
            public boolean hasPermission(String externalId, long departmentId, String permission) {
                return allowed;
            }
        };
    }

    static class DemoController {
        @RequirePermission("item:read")
        public void guarded() {
        }

        public void open() {
        }
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Method method = DemoController.class.getMethod(methodName);
        return new HandlerMethod(new DemoController(), method);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-MelonHub-User", "u1");
        request.addHeader("X-MelonHub-Department", "1");
        return request;
    }

    @Test
    void allowsWhenPermissionGranted() throws Exception {
        MelonHubPermissionInterceptor interceptor = new MelonHubPermissionInterceptor(stubAuthorizer(true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request(), response, handler("guarded"))).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void passesThroughWhenNoAnnotation() throws Exception {
        MelonHubPermissionInterceptor interceptor = new MelonHubPermissionInterceptor(stubAuthorizer(false));
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("open"))).isTrue();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void rejectsMissingHeadersWith401() throws Exception {
        MelonHubPermissionInterceptor interceptor = new MelonHubPermissionInterceptor(stubAuthorizer(true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), response, handler("guarded"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    private static MelonHubAuthorizer throwingAuthorizer(RuntimeException error) {
        return new MelonHubAuthorizer(new MelonHubClient("http://127.0.0.1:1", "mh_test")) {
            @Override
            public boolean hasPermission(String externalId, long departmentId, String permission) {
                throw error;
            }
        };
    }

    @Test
    void reportsDepartmentMismatchWith409() throws Exception {
        MelonHubPermissionInterceptor interceptor = new MelonHubPermissionInterceptor(
                throwingAuthorizer(new DepartmentMismatchException("DEPARTMENT_MISMATCH", "需要切换部门", "r1")));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request(), response, handler("guarded"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("DEPARTMENT_MISMATCH");
    }

    @Test
    void platformRefusalIsAnsweredWith403InsteadOfEscaping() throws Exception {
        MelonHubPermissionInterceptor interceptor = new MelonHubPermissionInterceptor(
                throwingAuthorizer(new MelonHubApiException(404, "NOT_FOUND", "用户不存在", "r2")));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request(), response, handler("guarded"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("NOT_FOUND");
    }

    @Test
    void headerTextCannotBreakTheErrorJson() throws Exception {
        MelonHubPermissionInterceptor interceptor = new MelonHubPermissionInterceptor(stubAuthorizer(true));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-MelonHub-User", "u1");
        request.addHeader("X-MelonHub-Department", "1\\\",\"admin\":\"true");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, handler("guarded"))).isFalse();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertThat(json.has("admin")).isFalse();
        assertThat(json.path("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void rejectsDeniedPermissionWith403() throws Exception {
        MelonHubPermissionInterceptor interceptor = new MelonHubPermissionInterceptor(stubAuthorizer(false));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request(), response, handler("guarded"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FORBIDDEN");
    }
}
