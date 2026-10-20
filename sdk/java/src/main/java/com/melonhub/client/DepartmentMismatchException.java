package com.melonhub.client;

/**
 * 旧版服务的部门不匹配异常（HTTP 409，code=DEPARTMENT_MISMATCH）。
 * V6 服务按软件授权，保留此类型兼容旧服务响应。
 */
public class DepartmentMismatchException extends MelonHubApiException {

    public DepartmentMismatchException(String code, String message, String requestId) {
        super(409, code, message, requestId);
    }
}
