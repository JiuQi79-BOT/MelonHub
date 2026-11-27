package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.IdentityCredential;
import com.melonhub.rbac.infrastructure.persistence.entity.IdentitySession;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface IdentityCredentialRepository extends JpaRepository<IdentityCredential, Long> {
    interface ActiveCredential {
        long getUserId();
        String getUsername();
        String getPasswordHash();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from IdentityCredential c where c.userId = :userId")
    Optional<IdentityCredential> findLockedByUserId(long userId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from IdentityCredential c where c.userId = :userId")
    Optional<IdentityCredential> findReadLockedByUserId(long userId);

    @Query("""
            select c.userId as userId, c.username as username, c.passwordHash as passwordHash
            from IdentityCredential c join c.user u
            where c.username = :username and c.enabled = true and u.enabled = true""")
    Optional<ActiveCredential> findActiveByUsername(String username);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update IdentityCredential c set c.username = :username where c.userId = :userId")
    int renameUsername(long userId, String username);

    @Transactional
    default void upsertCredential(long userId, String username, String passwordHash) {
        IdentityCredential credential = findLockedByUserId(userId).orElse(null);
        if (credential == null) {
            saveAndFlush(new IdentityCredential(userId, username, passwordHash, true));
        } else {
            credential.setUsername(username).setPasswordHash(passwordHash).setEnabled(true);
            saveAndFlush(credential);
        }
    }

    @Transactional
    default boolean createSessionIfVerified(UserRepository users, IdentitySessionRepository sessions,
                                            String tokenHash, long userId, String username, String passwordHash,
                                            String audience, Instant expiresAt) {
        User user = users.findReadLockedById(userId).orElse(null);
        if (user == null || !user.isEnabled() || !user.getExternalId().equals(username)) return false;
        IdentityCredential current = findReadLockedByUserId(userId).orElse(null);
        if (current == null || !current.isEnabled() || !current.getUsername().equals(username)
                || !current.getPasswordHash().equals(passwordHash)) {
            return false;
        }
        sessions.saveAndFlush(new IdentitySession(tokenHash, user, audience, expiresAt));
        return true;
    }
}
