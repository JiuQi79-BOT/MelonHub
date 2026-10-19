package com.melonhub.client;

/** 网络、超时、解析等传输层失败（无 HTTP 状态码）。 */
public class MelonHubException extends RuntimeException {

    public MelonHubException(String message, Throwable cause) {
        super(message, cause);
    }
}
