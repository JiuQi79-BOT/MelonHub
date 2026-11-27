package com.melonhub.rbac.api.governance.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * 定期复核列表项响应 VO（不含 {@code summary} 字段）。
 */
public record ReviewSummaryVO(
        long id,
        String name,
        long applicationId,
        String applicationName,
        Long departmentId,
        String departmentName,
        String status,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant dueAt,
        String createdBy,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt,
        String closedBy,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant closedAt,
        long items,
        long pending,
        long kept,
        long revoked,
        long skipped
) {}
