package com.melonhub.rbac.service.port.webhook;

import java.time.Instant;
import java.util.List;

/**
 * Webhook 订阅管理与投递日志的应用层命令与结果模型。
 */
public final class WebhookModels {
    private WebhookModels() {}

    public record SaveWebhookCommand(String name, String url, String secret, List<String> events, Boolean enabled) {}

    public record WebhookResult(
            long id,
            String name,
            String url,
            List<String> events,
            boolean enabled,
            String createdBy,
            Instant createdAt,
            long pending,
            long failed,
            Instant lastDeliveredAt
    ) {}

    public record WebhookDetailResult(
            long id,
            long applicationId,
            String name,
            String url,
            List<String> events,
            boolean enabled,
            String createdBy,
            Instant createdAt
    ) {}

    public record WebhookCreatedResult(long id, String secret) {}

    public record WebhookDeliveryResult(
            long id,
            String eventId,
            String eventType,
            String status,
            int attempts,
            Instant nextAttemptAt,
            Integer lastStatus,
            String lastError,
            Instant createdAt,
            Instant deliveredAt
    ) {}
}
