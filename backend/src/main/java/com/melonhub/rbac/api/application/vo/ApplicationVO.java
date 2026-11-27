package com.melonhub.rbac.api.application.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 业务软件列表项响应 VO。
 */
public record ApplicationVO(
        long id,
        String name,
        String code,
        String description,
        String owner,
        boolean enabled,
        Integer rateLimit,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt,
        long roleCount,
        long permissionCount,
        long activeKeyCount
) {}
