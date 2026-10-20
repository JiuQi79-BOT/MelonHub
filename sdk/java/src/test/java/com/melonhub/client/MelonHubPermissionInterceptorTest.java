package com.melonhub.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

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

    @RequirePermission("item:read")
    static class GuardedBaseController {
        public void guarded() {
        }
    }

    static class InheritedController extends GuardedBaseController {
    }

    @RequirePermission("item:read")
    interface GuardedApi {
        void guarded();
    }

    static class InterfaceController implements GuardedApi {
        @Override
        public void guarded() {
        }
    }

    @RequirePermission("item:write")
    static class OverridingController extends InheritedController {
        @RequirePermission("item:delete")
        public void methodGuarded() {
        }
    }

    private static Stream<HandlerMethod> classGuardedHandlers() throws NoSuchMethodException {
        Method inherited = GuardedBaseController.class.getMethod("guarded");
        return Stream.of(new HandlerMethod(new GuardedBaseController(), inherited),
                new HandlerMethod(new InheritedController(), inherited),
                new HandlerMethod(new InterfaceController(), "guarded"),
                new HandlerMethod(new OverridingController(), inherited));
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

    @ParameterizedTest
    @MethodSource("classGuardedHandlers")
    void classPermissionRequiresIdentityHeaders(HandlerMethod handler) throws Exception {
        var interceptor = new MelonHubPermissionInterceptor(stubAuthorizer(true));
        var response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), response, handler)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @ParameterizedTest
    @MethodSource("classGuardedHandlers")
    void classPermissionDeniesUnauthorizedUsers(HandlerMethod handler) throws Exception {
        var interceptor = new MelonHubPermissionInterceptor(stubAuthorizer(false));
        var response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request(), response, handler)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @MethodSource("classGuardedHandlers")
    void classPermissionAllowsAuthorizedUsers(HandlerMethod handler) throws Exception {
        var interceptor = new MelonHubPermissionInterceptor(stubAuthorizer(true));
        assertThat(interceptor.preHandle(request(), new MockHttpServletResponse(), handler)).isTrue();
    }

    @Test
    void methodPermissionOverridesClassAndClassOverridesSuperclass() throws Exception {
        var checkedPermission = new AtomicReference<String>();
        var authorizer = new MelonHubAuthorizer(new MelonHubClient("http://127.0.0.1:1", "mh_test")) {
            @Override
            public boolean hasPermission(String externalId, long departmentId, String permission) {
                checkedPermission.set(permission);
                return true;
            }
        };
        var interceptor = new MelonHubPermissionInterceptor(authorizer);
        var controller = new OverridingController();
        assertThat(interceptor.preHandle(request(), new MockHttpServletResponse(),
                new HandlerMethod(controller, "guarded"))).isTrue();
        assertThat(checkedPermission.get()).isEqualTo("item:write");
        assertThat(interceptor.preHandle(request(), new MockHttpServletResponse(),
                new HandlerMethod(controller, "methodGuarded"))).isTrue();
        assertThat(checkedPermission.get()).isEqualTo("item:delete");
    }
}
