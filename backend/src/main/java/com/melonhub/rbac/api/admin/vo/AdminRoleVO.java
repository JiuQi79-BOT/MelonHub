package com.melonhub.rbac.api.admin.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;
import java.util.List;

/**
 * 管理员角色列表项 VO。
 */
public record AdminRoleVO(
        String code,
        String name,
        String description,
        boolean builtin,
        List<String> functions,
        long accountCount,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt
) {}
