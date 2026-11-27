package com.melonhub.rbac.api.governance.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 定期复核单条授权快照项 VO。
 */
public record ReviewItemVO(
        long id,
        Long userId,
        String userName,
        String externalId,
        Long roleId,
        String roleName,
        String roleCode,
        Long departmentId,
        String departmentName,
        String decision,
        String comment,
        String decidedBy,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant decidedAt,
        boolean applied,
        boolean skipped
) {}
