package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Webhook;
import com.melonhub.rbac.infrastructure.persistence.entity.WebhookDelivery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {
    String SENDING_LEASE_ERROR = "Delivery lease expired after final attempt";

    interface WebhookDeliveryStats {
        long getWebhookId();
        long getPending();
        long getFailed();
        Instant getLastDeliveredAt();
    }

    interface DueDelivery {
        long getId();
        String getEventId();
        String getEventType();
        String getPayload();
        int getAttempts();
        String getUrl();
        String getSecret();
    }

    interface DeliverySummary {
        long getId();
        String getEventId();
        String getEventType();
        String getStatus();
        int getAttempts();
        Instant getNextAttemptAt();
        Integer getLastStatus();
        String getLastError();
        Instant getCreatedAt();
        Instant getDeliveredAt();
    }

    @Query("""
            select d.webhook.id as webhookId,
                   coalesce(sum(case when d.status = 'PENDING' then 1L else 0L end), 0L) as pending,
                   coalesce(sum(case when d.status = 'FAILED' then 1L else 0L end), 0L) as failed,
                   max(case when d.status = 'DELIVERED' then d.deliveredAt end) as lastDeliveredAt
            from WebhookDelivery d where d.webhook.application.id = :applicationId group by d.webhook.id""")
    List<WebhookDeliveryStats> findStatsByApplicationId(long applicationId);

    @Query("""
            select d.id as id, d.eventId as eventId, d.eventType as eventType, d.payload as payload,
                   d.attempts as attempts, w.url as url, w.secret as secret
            from WebhookDelivery d join d.webhook w
            where d.status = 'PENDING' and d.nextAttemptAt <= current_timestamp and w.enabled = true
            order by d.id""")
    List<DueDelivery> findDueDeliveries(Limit limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update WebhookDelivery d set d.status = 'SENDING', d.attempts = d.attempts + 1, d.nextAttemptAt = :leaseUntil
            where d.id = :id and d.status = 'PENDING' and d.attempts = :attempts and d.nextAttemptAt <= current_timestamp
              and exists (select 1 from Webhook w where w.id = d.webhook.id and w.enabled = true)""")
    int claimDelivery(long id, int attempts, Instant leaseUntil);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update WebhookDelivery d set d.status = 'PENDING', d.attempts = d.attempts - 1, d.nextAttemptAt = current_timestamp
            where d.id = :id and d.status = 'SENDING' and d.attempts = :attempt""")
    int releaseClaim(long id, int attempt);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update WebhookDelivery d set d.status = case when d.attempts >= :maxAttempts then 'FAILED' else 'PENDING' end,
                   d.lastError = case when d.attempts >= :maxAttempts then :error else d.lastError end
            where d.status = 'SENDING' and d.nextAttemptAt <= current_timestamp""")
    int requeueStale(int maxAttempts, String error);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update WebhookDelivery d set d.status = 'DELIVERED', d.deliveredAt = current_timestamp,
                   d.lastStatus = :status, d.lastError = ''
            where d.id = :id and d.status = 'SENDING' and d.attempts = :attempt""")
    int markDelivered(long id, int attempt, int status);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update WebhookDelivery d set d.status = 'PENDING', d.nextAttemptAt = :nextAttemptAt,
                   d.lastStatus = :status, d.lastError = :error
            where d.id = :id and d.status = 'SENDING' and d.attempts = :attempt""")
    int scheduleRetry(long id, int attempt, Instant nextAttemptAt, Integer status, String error);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update WebhookDelivery d set d.status = 'FAILED', d.lastStatus = :status, d.lastError = :error
            where d.id = :id and d.status = 'SENDING' and d.attempts = :attempt""")
    int markFailed(long id, int attempt, Integer status, String error);

    @Query(value = """
            select d.id as id, d.eventId as eventId, d.eventType as eventType, d.status as status,
                   d.attempts as attempts, d.nextAttemptAt as nextAttemptAt, d.lastStatus as lastStatus,
                   d.lastError as lastError, d.createdAt as createdAt, d.deliveredAt as deliveredAt
            from WebhookDelivery d where d.webhook.id = :webhookId order by d.id desc""",
            countQuery = "select count(d) from WebhookDelivery d where d.webhook.id = :webhookId")
    Page<DeliverySummary> findPageByWebhookId(long webhookId, Pageable pageable);

    @Query("""
            select d.id from WebhookDelivery d
            where d.createdAt < :cutoff and d.status in ('DELIVERED', 'FAILED') order by d.id""")
    List<Long> findPurgeableIds(Instant cutoff, Limit limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from WebhookDelivery d where d.id in :ids")
    int deleteByIdIn(Collection<Long> ids);

    @Query("select count(d) from WebhookDelivery d where d.status = 'PENDING'")
    long countPendingBacklog();

    @Transactional
    default int claim(long id, int attempts) {
        return claimDelivery(id, attempts, JpaQuery.now().plus(5, ChronoUnit.MINUTES));
    }

    @Transactional
    default int requeueStale(int maxAttempts) {
        return requeueStale(maxAttempts, SENDING_LEASE_ERROR);
    }

    @Transactional
    default int retry(long id, int attempt, int delayMinutes, Integer status, String error) {
        return scheduleRetry(id, attempt, JpaQuery.now().plus(delayMinutes, ChronoUnit.MINUTES),
                status, JpaQuery.truncate(error, 300));
    }

    @Transactional
    default int fail(long id, int attempt, Integer status, String error) {
        return markFailed(id, attempt, status, JpaQuery.truncate(error, 300));
    }

    @Transactional
    default int purgeFinished(int days, int limit) {
        var ids = findPurgeableIds(JpaQuery.now().minus(days, ChronoUnit.DAYS), Limit.of(limit));
        return ids.isEmpty() ? 0 : deleteByIdIn(ids);
    }

    @Transactional
    default int enqueueMatching(List<Webhook> webhooks, String type, String payload) {
        Instant now = JpaQuery.now();
        var batch = new ArrayList<WebhookDelivery>();
        for (Webhook webhook : webhooks) {
            var events = Arrays.asList(webhook.getEvents().split(","));
            if (!events.contains("*") && !events.contains(type)) continue;
            batch.add(newDelivery(webhook, type, payload, now));
        }
        if (batch.isEmpty()) return 0;
        saveAllAndFlush(batch);
        return batch.size();
    }

    @Transactional
    default void enqueueTest(Webhook webhookRef, String payload) {
        saveAndFlush(newDelivery(webhookRef, "webhook.test", payload, JpaQuery.now()));
    }

    private static WebhookDelivery newDelivery(Webhook webhook, String type, String payload, Instant now) {
        return new WebhookDelivery()
                .setWebhook(webhook)
                .setEventId(UUID.randomUUID().toString())
                .setEventType(type)
                .setPayload(payload)
                .setStatus("PENDING")
                .setAttempts(0)
                .setNextAttemptAt(now)
                .setLastError("");
    }
}
