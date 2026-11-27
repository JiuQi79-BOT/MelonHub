package com.melonhub.rbac.api.webhook.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;
import java.util.List;

/**
 * Webhook 订阅列表项响应 VO。
 */
public record WebhookVO(
        long id,
        String name,
        String url,
        List<String> events,
        boolean enabled,
        String createdBy,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt,
        long pending,
        long failed,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant lastDeliveredAt
) {}
