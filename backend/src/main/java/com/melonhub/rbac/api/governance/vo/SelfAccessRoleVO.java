package com.melonhub.rbac.api.governance.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 业务系统员工自查响应中的单个角色 VO。
 */
public record SelfAccessRoleVO(
        long id,
        String name,
        String code,
        boolean enabled,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant expiresAt
) {}
