package com.melonhub.rbac.api.domainrbac.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;
import java.util.List;

/**
 * 隔离域角色列表项响应 VO（含 RBAC1 父角色继承列表）。
 */
public record RoleVO(
        long id,
        String name,
        String code,
        String description,
        boolean enabled,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt,
        long memberCount,
        List<Long> permissionIds,
        List<Long> parentRoleIds
) {}
