package com.melonhub.rbac.api.domainrbac.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 单个用户在隔离域内的角色分配与到期时间响应 VO。
 */
public record UserRoleAssignmentsVO(
        List<Long> roleIds,
        @JsonSerialize(contentUsing = ApiTimestampSerializer.class) Map<Long, Instant> roleExpiries
) {}
