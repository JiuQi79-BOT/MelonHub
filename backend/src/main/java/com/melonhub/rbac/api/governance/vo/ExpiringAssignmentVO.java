package com.melonhub.rbac.api.governance.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 即将到期角色分配列表项 VO。
 */
public record ExpiringAssignmentVO(
        long userId,
        String userName,
        String externalId,
        long roleId,
        String roleName,
        String roleCode,
        long departmentId,
        String departmentName,
        long applicationId,
        String applicationName,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant expiresAt,
        String grantedBy,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant grantedAt
) {}
