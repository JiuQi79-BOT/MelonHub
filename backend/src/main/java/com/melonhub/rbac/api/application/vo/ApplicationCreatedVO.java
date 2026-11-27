package com.melonhub.rbac.api.application.vo;

/**
 * 新建软件及默认 API Key 一次性响应 VO。
 */
public record ApplicationCreatedVO(long id, String apiKey, long keyId) {}
