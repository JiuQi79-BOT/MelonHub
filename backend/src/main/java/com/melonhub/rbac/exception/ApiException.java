package com.melonhub.rbac.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Integer retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String message) { this(status, code, message, null); }
    public ApiException(HttpStatus status, String code, String message, Integer retryAfterSeconds) {
        super(message); this.status = status; this.code = code; this.retryAfterSeconds = retryAfterSeconds;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }

    public static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    public static ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message); }
    public static ApiException unauthorized() { return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请登录或检查接口凭据"); }
    public static ApiException missing() { return missing("记录不存在"); }
    public static ApiException missing(String message) { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message); }
    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message); }
    public static ApiException contextInvalid() { return new ApiException(HttpStatus.UNAUTHORIZED, "CONTEXT_INVALID", "权限上下文已失效，请重新创建"); }
    public static ApiException busy(String code, String message) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message, 1); }
    public static ApiException rateLimited(String message) { return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message, 1); }
}
