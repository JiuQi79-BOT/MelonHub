package com.melonhub.rbac.api.admin.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;
import java.util.List;

/**
 * 管理员账号列表项响应 VO（{@code locked} 与 {@code mfaEnabled} 保持 0/1 契约值）。
 */
public record AdminAccountVO(
        long id,
        String username,
        String displayName,
        String role,
        boolean enabled,
        int failedAttempts,
        long locked,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant lockedUntil,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant lastLoginAt,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant passwordChangedAt,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt,
        long activeSessions,
        long mfaEnabled,
        boolean allApplications,
        Long parentId,
        String parentUsername,
        String kind,
        boolean allDepartments,
        List<Long> applicationIds,
        List<Long> departmentIds,
        boolean manageable
) {}
