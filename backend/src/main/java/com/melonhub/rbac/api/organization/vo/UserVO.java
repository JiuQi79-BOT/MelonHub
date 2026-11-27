package com.melonhub.rbac.api.organization.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;
import java.util.List;

/**
 * 用户详情及列表项响应 VO。
 */
public record UserVO(
        long id,
        String name,
        String externalId,
        String email,
        boolean enabled,
        Long activeDepartmentId,
        long contextVersion,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt,
        List<Long> departmentIds
) {}
