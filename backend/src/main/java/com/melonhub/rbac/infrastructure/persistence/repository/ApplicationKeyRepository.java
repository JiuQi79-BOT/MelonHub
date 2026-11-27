package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.ApplicationKey;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ApplicationKeyRepository extends JpaRepository<ApplicationKey, Long>, ApplicationKeyRepositoryCustom {
    interface KeyState {
        long getKeyId();
        long getApplicationId();
        boolean isApplicationEnabled();
        Instant getRevokedAt();
        Instant getExpiresAt();
    }

    interface KeySummary {
        long getId();
        String getName();
        String getPrefix();
        String getCreatedBy();
        Instant getCreatedAt();
        Instant getExpiresAt();
        Instant getLastUsedAt();
        Instant getRevokedAt();
        String getStatus();
    }

    interface ApplicationCount {
        long getApplicationId();
        long getCount();
    }

    @Query("""
            select k.id as keyId, a.id as applicationId, a.enabled as applicationEnabled, k.revokedAt as revokedAt, k.expiresAt as expiresAt
            from ApplicationKey k join k.application a where k.keyHash = :hash""")
    Optional<KeyState> byHash(String hash);

    @Query("""
            select k.id as id, k.name as name, k.keyPrefix as prefix, k.createdBy as createdBy, k.createdAt as createdAt,
                   k.expiresAt as expiresAt, k.lastUsedAt as lastUsedAt, k.revokedAt as revokedAt,
                   case when k.revokedAt is not null then 'REVOKED'
                        when k.expiresAt is not null and k.expiresAt <= current_timestamp then 'EXPIRED'
                        else 'ACTIVE' end as status
            from ApplicationKey k where k.application.id = :applicationId order by k.id desc""")
    List<KeySummary> findSummariesByApplicationId(long applicationId);

    @Query("""
            select count(k) from ApplicationKey k where k.application.id = :applicationId
              and k.revokedAt is null and (k.expiresAt is null or k.expiresAt > current_timestamp)""")
    long countActiveByApplicationId(long applicationId);

    @Query("""
            select k.application.id as applicationId, count(k) as count from ApplicationKey k
            where k.application.id in :applicationIds and k.revokedAt is null
              and (k.expiresAt is null or k.expiresAt > current_timestamp)
            group by k.application.id""")
    List<ApplicationCount> countActiveByApplicationIds(Collection<Long> applicationIds);

    @Query("select k.name from ApplicationKey k where k.id = :keyId and k.application.id = :applicationId")
    Optional<String> findNameByIdAndApplicationId(long keyId, long applicationId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ApplicationKey k set k.revokedAt = current_timestamp where k.id = :keyId and k.application.id = :applicationId and k.revokedAt is null")
    int revokeOne(long keyId, long applicationId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ApplicationKey k set k.revokedAt = current_timestamp where k.application.id = :applicationId and k.revokedAt is null")
    int revokeAll(long applicationId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ApplicationKey k set k.lastUsedAt = :now where k.id in :ids")
    int markUsed(Collection<Long> ids, Instant now);
}
