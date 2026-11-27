package com.melonhub.rbac.api.audit.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 审计日志列表项响应 VO。
 */
public record AuditEventVO(
        long id,
        String actor,
        String action,
        String target,
        String detail,
        String sourceIp,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt,
        Long departmentId,
        String departmentName,
        Long applicationId,
        String applicationName
) {}
