package com.melonhub.rbac.api.domainrbac.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 隔离域权限项响应 VO。
 */
public record PermissionVO(
        long id,
        String name,
        String code,
        String resource,
        String action,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt,
        long roleCount
) {}
