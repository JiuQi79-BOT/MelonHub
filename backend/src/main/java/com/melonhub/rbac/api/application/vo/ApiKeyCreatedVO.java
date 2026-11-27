package com.melonhub.rbac.api.application.vo;

import java.time.Instant;

/**
 * 创建 API Key 一次性响应 VO（{@code expiresAt} 保持 ISO-8601 UTC 格式）。
 */
public record ApiKeyCreatedVO(long id, String name, String prefix, String apiKey, Instant expiresAt) {}
