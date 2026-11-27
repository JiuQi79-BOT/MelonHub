package com.melonhub.rbac.api.organization.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 用户活跃权限上下文摘要 VO（{@code valid} 保持 0/1 契约值）。
 */
public record ActiveContextVO(
        long departmentId,
        String departmentName,
        long applicationId,
        String applicationName,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant expiresAt,
        long activeRoleCount,
        long valid
) {}
