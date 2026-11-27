package com.melonhub.rbac.api.domainrbac.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 隔离域成员授权分页项响应 VO。
 */
public record DomainAssignmentVO(
        long id,
        String name,
        String externalId,
        String email,
        boolean enabled,
        List<Long> roleIds,
        @JsonSerialize(contentUsing = ApiTimestampSerializer.class) Map<Long, Instant> roleExpiries
) {}
