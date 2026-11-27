package com.melonhub.rbac.service.port.error;

import org.springframework.http.HttpStatus;

/**
 * 平台统一业务异常，携带 HTTP 状态码、稳定错误码与中文提示信息。
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Integer retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException bad(String message) {
        return bad("INVALID_REQUEST", message);
    }

    public static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException forbidden(String message) {
        return forbidden("FORBIDDEN", message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException unauthorized() {
        return unauthorized("UNAUTHORIZED", "请登录或检查接口凭据");
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static ApiException missing(String message) {
        return missing("NOT_FOUND", message);
    }

    public static ApiException missing(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String message) {
        return conflict("CONFLICT", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException contextInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "CONTEXT_INVALID", "权限上下文已失效，请重新创建");
    }

    public static ApiException busy(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message, 1);
    }

    public static ApiException rateLimited(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message, 1);
    }
}
