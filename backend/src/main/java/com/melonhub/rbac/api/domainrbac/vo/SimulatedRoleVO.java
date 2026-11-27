package com.melonhub.rbac.api.domainrbac.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 权限诊断中的角色状态 VO。
 */
public record SimulatedRoleVO(
        long id,
        String name,
        String code,
        boolean enabled,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant expiresAt,
        boolean expired,
        boolean active
) {}
