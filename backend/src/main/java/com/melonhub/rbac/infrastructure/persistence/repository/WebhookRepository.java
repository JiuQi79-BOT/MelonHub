package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Webhook;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface WebhookRepository extends JpaRepository<Webhook, Long> {
    interface WebhookSummary {
        long getId();
        String getName();
        String getUrl();
        String getEvents();
        boolean isEnabled();
        String getCreatedBy();
        Instant getCreatedAt();
    }

    interface WebhookDetail {
        long getId();
        long getApplicationId();
        String getName();
        String getUrl();
        String getEvents();
        boolean isEnabled();
        String getCreatedBy();
        Instant getCreatedAt();
    }

    @Query("""
            select w.id as id, w.name as name, w.url as url, w.events as events, w.enabled as enabled,
                   w.createdBy as createdBy, w.createdAt as createdAt
            from Webhook w where w.application.id = :applicationId order by w.id""")
    List<WebhookSummary> findSummariesByApplicationId(long applicationId);

    @Query("""
            select w.id as id, w.application.id as applicationId, w.name as name, w.url as url,
                   w.events as events, w.enabled as enabled, w.createdBy as createdBy, w.createdAt as createdAt
            from Webhook w where w.id = :id""")
    Optional<WebhookDetail> findDetailById(long id);

    @Query("select w from Webhook w where w.application.id = :applicationId and w.enabled = true order by w.id")
    List<Webhook> findEnabledByApplicationId(long applicationId);

    @Query("select w from Webhook w where w.enabled = true order by w.id")
    List<Webhook> findAllEnabled();

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Webhook w set w.name = :name, w.url = :url, w.events = :events, w.enabled = :enabled where w.id = :id")
    int updateProfile(long id, String name, String url, String events, boolean enabled);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Webhook w set w.secret = :secret where w.id = :id")
    int updateSecret(long id, String secret);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Webhook w where w.id = :id")
    int deleteByIdReturningCount(long id);
}
