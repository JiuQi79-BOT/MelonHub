package com.melonhub.client;

/**
 * MelonHub 平台返回的业务/系统错误。4xx/5xx 均抛出本异常（429/503 已按 Retry-After 内部重试）。
 */
public class MelonHubApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final String requestId;

    public MelonHubApiException(int status, String code, String message, String requestId) {
        super(message);
        this.status = status;
        this.code = code;
        this.requestId = requestId;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getRequestId() {
        return requestId;
    }
}
