package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.IdentitySession;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface IdentitySessionRepository extends JpaRepository<IdentitySession, Long> {
    @Query("""
            select count(s) > 0 from IdentitySession s
            where s.tokenHash = :tokenHash and s.revokedAt is null and s.expiresAt > :now and s.user.enabled = true""")
    boolean hasActiveSession(String tokenHash, Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update IdentitySession s set s.revokedAt = :revokedAt where s.tokenHash = :tokenHash and s.revokedAt is null")
    int revokeByTokenHash(String tokenHash, Instant revokedAt);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update IdentitySession s set s.revokedAt = :revokedAt where s.user.id = :userId and s.revokedAt is null")
    int revokeActiveByUserId(long userId, Instant revokedAt);

    @Transactional
    default int revokeUserSessions(long userId) {
        return revokeActiveByUserId(userId, Instant.now());
    }
}
