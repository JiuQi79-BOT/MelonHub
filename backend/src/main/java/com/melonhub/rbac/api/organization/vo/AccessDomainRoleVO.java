package com.melonhub.rbac.api.organization.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 用户权限全览中单个隔离域内的角色明细 VO。
 */
public record AccessDomainRoleVO(
        long id,
        String name,
        String code,
        boolean enabled,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant expiresAt,
        String grantedBy
) {}
