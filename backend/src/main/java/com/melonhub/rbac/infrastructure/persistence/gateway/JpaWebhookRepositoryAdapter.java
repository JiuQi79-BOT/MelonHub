package com.melonhub.rbac.infrastructure.persistence.gateway;

import com.melonhub.rbac.infrastructure.persistence.entity.Webhook;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.WebhookRepository;
import com.melonhub.rbac.service.ddd.domain.model.WebhookEndpointAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.PageSlice;
import com.melonhub.rbac.service.ddd.domain.repository.WebhookRepositoryPort;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/** DDD 基础设施适配器：将 {@link WebhookRepositoryPort} 桥接到 Spring Data JPA 仓储。 */
@Repository
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class JpaWebhookRepositoryAdapter implements WebhookRepositoryPort {
    private final WebhookRepository webhooks;
    private final WebhookDeliveryRepository deliveries;
    private final ApplicationRepository applications;

    public JpaWebhookRepositoryAdapter(WebhookRepository webhooks, WebhookDeliveryRepository deliveries,
                                       ApplicationRepository applications) {
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.applications = applications;
    }

    @Override
    public List<WebhookListRecord> webhooks(long applicationId) {
        var rows = webhooks.findSummariesByApplicationId(applicationId);
        var stats = new HashMap<Long, WebhookDeliveryRepository.WebhookDeliveryStats>();
        for (var s : deliveries.findStatsByApplicationId(applicationId)) stats.put(s.getWebhookId(), s);
        return rows.stream().map(w -> {
            var s = stats.get(w.getId());
            return new WebhookListRecord(w.getId(), w.getName(), w.getUrl(), w.getEvents(), w.isEnabled(),
                    w.getCreatedBy(), w.getCreatedAt(),
                    s == null ? 0L : s.getPending(),
                    s == null ? 0L : s.getFailed(),
                    s == null ? null : s.getLastDeliveredAt());
        }).toList();
    }

    @Override
    public Optional<WebhookDetailRecord> webhookView(long id) {
        return webhooks.findDetailById(id).map(w -> new WebhookDetailRecord(
                w.getId(), w.getApplicationId(), w.getName(), w.getUrl(), w.getEvents(),
                w.isEnabled(), w.getCreatedBy(), w.getCreatedAt()));
    }

    @Override
    public Optional<WebhookEndpointAggregate> findWebhook(long id) {
        return webhooks.findDetailById(id).map(row -> {
            String rawEvents = row.getEvents();
            List<String> events = rawEvents == null || rawEvents.isBlank() ? List.of() : List.of(rawEvents.split(","));
            return new WebhookEndpointAggregate(row.getId(), row.getApplicationId(), row.getName(),
                    row.getUrl(), row.isEnabled(), events);
        });
    }

    @Override
    public long createWebhook(long applicationId, String name, String url, String secret, String events, boolean enabled, String actor) {
        return webhooks.saveAndFlush(new Webhook()
                .setApplication(applications.getReferenceById(applicationId))
                .setName(name).setUrl(url).setSecret(secret).setEvents(events)
                .setEnabled(enabled).setCreatedBy(actor)).getId();
    }

    @Override public int updateWebhook(long id, String name, String url, String events, boolean enabled) { return webhooks.updateProfile(id, name, url, events, enabled); }
    @Override public void updateWebhookSecret(long id, String secret) { webhooks.updateSecret(id, secret); }
    @Override public void deleteWebhook(long id) { webhooks.deleteByIdReturningCount(id); }

    @Override
    public PageSlice<DeliveryRecord> deliveries(long webhookId, int page, int size) {
        var p = deliveries.findPageByWebhookId(webhookId, PageRequest.of(Math.max(0, page - 1), Math.max(1, size)));
        return new PageSlice<>(p.getContent().stream().map(d -> new DeliveryRecord(
                d.getId(), d.getEventId(), d.getEventType(), d.getStatus(), d.getAttempts(),
                d.getNextAttemptAt(), d.getLastStatus(), d.getLastError(), d.getCreatedAt(), d.getDeliveredAt())).toList(),
                p.getTotalElements());
    }

    @Override public void enqueueTest(long webhookId, String payload) { deliveries.enqueueTest(webhooks.getReferenceById(webhookId), payload); }
    @Override public void emit(long applicationId, String eventType, String payload) { deliveries.enqueueMatching(webhooks.findEnabledByApplicationId(applicationId), eventType, payload); }
    @Override public void emitAll(String eventType, String payload) { deliveries.enqueueMatching(webhooks.findAllEnabled(), eventType, payload); }
    @Override public void requeueStale(int maxAttempts) { deliveries.requeueStale(maxAttempts); }

    @Override
    public List<DueDelivery> dueDeliveries(int limit) {
        return deliveries.findDueDeliveries(Limit.of(limit)).stream()
                .map(row -> new DueDelivery(row.getId(), row.getAttempts(), row.getUrl(), row.getSecret(),
                        row.getEventType(), row.getEventId(), row.getPayload()))
                .toList();
    }

    @Override public int claimDelivery(long id, int expectedAttempts) { return deliveries.claim(id, expectedAttempts); }
    @Override public void releaseDelivery(long id, int attempt) { deliveries.releaseClaim(id, attempt); }
    @Override public void markDelivered(long id, int attempt, int httpStatus) { deliveries.markDelivered(id, attempt, httpStatus); }
    @Override public void scheduleRetry(long id, int attempt, int delayMinutes, Integer httpStatus, String error) { deliveries.retry(id, attempt, delayMinutes, httpStatus, error); }
    @Override public void markFailed(long id, int attempt, Integer httpStatus, String error) { deliveries.fail(id, attempt, httpStatus, error); }
    @Override public int purgeDeliveries(int days, int limit) { return deliveries.purgeFinished(days, limit); }
}
