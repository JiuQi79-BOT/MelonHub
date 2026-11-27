package com.melonhub.rbac.api.governance.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 角色申请单响应 VO。
 */
public record AccessRequestVO(
        long id,
        Long userId,
        String userName,
        String externalId,
        Long departmentId,
        String departmentName,
        long applicationId,
        String applicationName,
        Long roleId,
        String roleName,
        String roleCode,
        Boolean roleEnabled,
        String reason,
        Integer durationDays,
        String status,
        String requestedBy,
        String decidedBy,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant decidedAt,
        String decisionComment,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant grantExpiresAt,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt
) {}
