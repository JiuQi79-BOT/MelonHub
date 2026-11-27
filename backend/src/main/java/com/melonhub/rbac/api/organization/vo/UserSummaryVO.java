package com.melonhub.rbac.api.organization.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 用户基础信息响应 VO（不含所属部门列表，用于权限模拟诊断）。
 */
public record UserSummaryVO(
        long id,
        String name,
        String externalId,
        String email,
        boolean enabled,
        Long activeDepartmentId,
        long contextVersion,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt
) {}
