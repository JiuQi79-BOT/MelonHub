package com.melonhub.rbac.api.organization.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 部门列表项响应 VO。
 */
public record DepartmentVO(
        long id,
        String name,
        String code,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt,
        long memberCount,
        long roleCount
) {}
