package com.melonhub.client;

/**
 * 上下文已失效/过期/跨软件（HTTP 401 且 code=CONTEXT_INVALID）。
 * 调用方应重新创建上下文后重试；{@link MelonHubAuthorizer} 会在同一部门内重建一次，不会切换部门。
 */
public class ContextInvalidException extends MelonHubApiException {

    public ContextInvalidException(String code, String message, String requestId) {
        super(401, code, message, requestId);
    }
}
