package com.melonhub.rbac.infrastructure.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import com.melonhub.rbac.service.port.security.ApplicationCaller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Authenticates every /api request before it reaches a controller. Access is denied by default: only the
 * health probe and login are public, /api/v1 needs an application key and everything else an admin session.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiGuard extends OncePerRequestFilter {
    /**
     * Spring MVC decodes percent escapes and drops ";" parameters before matching handlers, so the guard only
     * accepts paths that are already in that canonical form. Otherwise "/api;x/admin" would look public here but
     * still reach the admin controller.
     */
    private static final Pattern CANONICAL_PATH = Pattern.compile("/|(/[A-Za-z0-9_@-][A-Za-z0-9._@-]*)+");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final Logger log = LoggerFactory.getLogger(ApiGuard.class);

    private final AuthUseCase auth;
    private final ObjectMapper json;
    private final GlobalRateLimiter global;
    private final ApplicationRateLimits applicationLimits;
    private final int loginsPerMinute;

    public ApiGuard(AuthUseCase auth, ObjectMapper json, GlobalRateLimiter global, ApplicationRateLimits applicationLimits,
                    @Value("${melonhub.rate-limit.login-per-minute:10}") int loginsPerMinute) {
        this.auth = auth;
        this.json = json;
        this.global = global;
        this.applicationLimits = applicationLimits;
        this.loginsPerMinute = loginsPerMinute;
    }

    /** 在同一个请求编号下执行鉴权、分派控制器并记录已匹配的 API 请求。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        String supplied = request.getHeader("X-Request-Id");
        String requestId = supplied != null && REQUEST_ID.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Cache-Control", "no-store");
        boolean completed = false;
        try {
            try {
                authenticate(request);
            } catch (ApiException e) {
                reject(response, e);
                return;
            } catch (DataAccessException e) {
                log.warn("Authentication storage unavailable: {}", e.getClass().getSimpleName());
                reject(response, ApiException.busy("SERVICE_BUSY", "服务繁忙，请稍后重试"));
                return;
            }
            chain.doFilter(request, response);
            completed = true;
        } finally {
            try {
                logCompletion(request, requestId, completed ? response.getStatus() : 500, start);
            } finally {
                MDC.remove("requestId");
            }
        }
    }

    /** 使用 MVC 路由模板记录结果，避免把路径参数、查询串、请求头或请求体写入日志。 */
    private void logCompletion(HttpServletRequest request, String requestId, int status, long start) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(pattern instanceof String route) || route.equals("/api/health")
                || !(route.startsWith("/api/") || route.startsWith("/auth/"))) {
            return;
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        log.info("HTTP requestId={} method={} route={} status={} durationMs={}",
                requestId, request.getMethod(), route, status, durationMs);
    }

    private void authenticate(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !CANONICAL_PATH.matcher(path).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "请求路径格式不正确");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.equals("/api") && !lower.startsWith("/api/")) return;
        if (path.equals("/api/health")) return;
        if (path.equals("/api/auth/login")) {
            if (!global.tryAcquire("login", String.valueOf(request.getRemoteAddr()), loginsPerMinute, 60))
                throw ApiException.rateLimited("登录尝试过多，请稍后重试");
            return;
        }
        if (path.startsWith("/api/v1/")) {
            ApplicationCaller caller = auth.application(request.getHeader("X-API-Key"));
            int limit = applicationLimits.limit(caller.applicationId());
            if (!global.tryAcquire("app", String.valueOf(caller.applicationId()), limit, 1))
                throw ApiException.rateLimited("调用频率超过平台限制，请稍后重试");
            request.setAttribute(ApplicationCaller.ATTRIBUTE, caller);
            return;
        }
        request.setAttribute(AdminAccess.ATTRIBUTE, auth.admin(request.getHeader("Authorization")));
    }

    private record GuardError(String code, String message,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String requestId) {}

    private void reject(HttpServletResponse response, ApiException e) throws IOException {
        response.setStatus(e.status().value());
        if (e.retryAfterSeconds() != null) response.setHeader("Retry-After", String.valueOf(e.retryAfterSeconds()));
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), new GuardError(e.code(), e.getMessage(), MDC.get("requestId")));
    }
}
