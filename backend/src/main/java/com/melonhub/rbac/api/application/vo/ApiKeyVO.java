package com.melonhub.rbac.api.application.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 软件 API Key 元数据列表项响应 VO（永不回传明文密钥）。
 */
public record ApiKeyVO(
        long id,
        String name,
        String prefix,
        String createdBy,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant expiresAt,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant lastUsedAt,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant revokedAt,
        String status
) {}
