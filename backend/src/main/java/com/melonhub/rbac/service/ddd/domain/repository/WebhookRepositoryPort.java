package com.melonhub.rbac.service.ddd.domain.repository;

import com.melonhub.rbac.service.ddd.domain.model.WebhookEndpointAggregate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Webhook 订阅配置与事务外盒投递队列领域仓储端口。
 */
public interface WebhookRepositoryPort {
    record WebhookListRecord(long id, String name, String url, String events, boolean enabled, String createdBy,
                             Instant createdAt, long pending, long failed, Instant lastDeliveredAt) {}

    record WebhookDetailRecord(long id, long applicationId, String name, String url, String events, boolean enabled,
                               String createdBy, Instant createdAt) {}

    record DueDelivery(long id, int attempts, String url, String secret, String eventType, String eventId, String payload) {}

    record DeliveryRecord(long id, String eventId, String eventType, String status, int attempts,
                          Instant nextAttemptAt, Integer lastStatus, String lastError, Instant createdAt,
                          Instant deliveredAt) {}

    List<WebhookListRecord> webhooks(long applicationId);

    Optional<WebhookDetailRecord> webhookView(long id);

    Optional<WebhookEndpointAggregate> findWebhook(long id);

    long createWebhook(long applicationId, String name, String url, String secret, String events, boolean enabled, String actor);

    int updateWebhook(long id, String name, String url, String events, boolean enabled);

    void updateWebhookSecret(long id, String secret);

    void deleteWebhook(long id);

    PageSlice<DeliveryRecord> deliveries(long webhookId, int page, int size);

    void enqueueTest(long webhookId, String payload);

    void emit(long applicationId, String eventType, String payload);

    void emitAll(String eventType, String payload);

    void requeueStale(int maxAttempts);

    List<DueDelivery> dueDeliveries(int limit);

    int claimDelivery(long id, int expectedAttempts);

    void releaseDelivery(long id, int attempt);

    void markDelivered(long id, int attempt, int httpStatus);

    void scheduleRetry(long id, int attempt, int delayMinutes, Integer httpStatus, String error);

    void markFailed(long id, int attempt, Integer httpStatus, String error);

    int purgeDeliveries(int days, int limit);
}
