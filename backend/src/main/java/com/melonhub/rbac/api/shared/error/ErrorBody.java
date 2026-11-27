package com.melonhub.rbac.api.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

/**
 * 统一 HTTP 错误响应载荷 VO，附带当前请求追踪编号（MDC 中不存在时省略）。
 */
public record ErrorBody(String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) String requestId) {
    public static ErrorBody of(String code, String message) {
        return new ErrorBody(code, message, MDC.get("requestId"));
    }
}
