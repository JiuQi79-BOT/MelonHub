package com.melonhub.rbac.api.governance.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 权限反查中持有该权限的角色明细 VO。
 */
public record PermissionHolderRoleVO(
        long id,
        String name,
        String code,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant expiresAt
) {}
