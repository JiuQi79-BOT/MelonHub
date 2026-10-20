package com.melonhub.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring MVC 拦截器：按 {@link RequirePermission} 校验权限。
 * 身份取自请求头（默认 X-MelonHub-User / X-MelonHub-Department，可在构造器配置）；
 * 缺头返回 401，无权限或平台拒绝返回 403，用户当前部门不是请求部门返回 409（DEPARTMENT_MISMATCH，需由业务系统
 * 引导用户显式切换部门），响应体为平台风格的 JSON 错误。
 */
public class MelonHubPermissionInterceptor implements HandlerInterceptor {

    public static final String DEFAULT_USER_HEADER = "X-MelonHub-User";
    public static final String DEFAULT_DEPARTMENT_HEADER = "X-MelonHub-Department";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MelonHubAuthorizer authorizer;
    private final String userHeader;
    private final String departmentHeader;

    public MelonHubPermissionInterceptor(MelonHubAuthorizer authorizer) {
        this(authorizer, DEFAULT_USER_HEADER, DEFAULT_DEPARTMENT_HEADER);
    }

    public MelonHubPermissionInterceptor(MelonHubAuthorizer authorizer, String userHeader, String departmentHeader) {
        this.authorizer = authorizer;
        this.userHeader = userHeader;
        this.departmentHeader = departmentHeader;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        RequirePermission required = findAnnotation(handler);
        if (required == null) {
            return true;
        }
        String externalId = request.getHeader(userHeader);
        String department = request.getHeader(departmentHeader);
        if (externalId == null || externalId.isBlank() || department == null || department.isBlank()) {
            writeError(response, 401, "UNAUTHORIZED", "缺少身份请求头 " + userHeader + " / " + departmentHeader);
            return false;
        }
        long departmentId;
        try {
            departmentId = Long.parseLong(department.trim());
        } catch (NumberFormatException e) {
            writeError(response, 401, "UNAUTHORIZED", "部门编号非法: " + department);
            return false;
        }
        boolean allowed;
        try {
            allowed = authorizer.hasPermission(externalId, departmentId, required.value());
        } catch (DepartmentMismatchException e) {
            writeError(response, 409, e.getCode(), e.getMessage());
            return false;
        } catch (MelonHubApiException e) {
            // 用户不存在、已停用、不属于该部门等平台拒绝，同样按无权限处理
            writeError(response, 403, e.getCode() == null ? "FORBIDDEN" : e.getCode(), e.getMessage());
            return false;
        }
        if (!allowed) {
            writeError(response, 403, "FORBIDDEN", "缺少权限: " + required.value());
            return false;
        }
        return true;
    }

    private static RequirePermission findAnnotation(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return null;
        }
        RequirePermission onMethod = method.getMethodAnnotation(RequirePermission.class);
        return onMethod != null ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequirePermission.class);
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 消息里可能带请求头原文，交给 Jackson 转义，避免拼出被篡改结构的 JSON
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        response.getOutputStream().write(JSON.writeValueAsBytes(body));
    }
}
