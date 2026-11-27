package com.melonhub.rbac.api.webhook.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.melonhub.rbac.api.shared.json.ApiTimestampSerializer;
import java.time.Instant;

/**
 * Webhook 投递记录列表项 VO。
 */
public record WebhookDeliveryVO(
        long id,
        String eventId,
        String eventType,
        String status,
        int attempts,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant nextAttemptAt,
        Integer lastStatus,
        String lastError,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant createdAt,
        @JsonSerialize(using = ApiTimestampSerializer.class) Instant deliveredAt
) {}
