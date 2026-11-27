package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.IdentityCredential;
import com.melonhub.rbac.infrastructure.persistence.entity.IdentitySession;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class IdentityCredentialRepositoryImpl implements IdentityCredentialRepositoryCustom {
    private final EntityManager em;

    IdentityCredentialRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public void upsertCredential(long userId, String username, String passwordHash) {
        IdentityCredential credential = em.find(IdentityCredential.class, userId, LockModeType.PESSIMISTIC_WRITE);
        if (credential == null) {
            em.persist(new IdentityCredential(userId, username, passwordHash, true));
        } else {
            credential.setUsername(username).setPasswordHash(passwordHash).setEnabled(true);
        }
        em.flush();
    }

    @Override
    public boolean createSessionIfVerified(String tokenHash, long userId, String username, String passwordHash,
                                           String audience, Instant expiresAt) {
        em.flush();
        em.clear();
        User user = em.find(User.class, userId, LockModeType.PESSIMISTIC_READ);
        if (user == null || !user.isEnabled() || !user.getExternalId().equals(username)) return false;
        IdentityCredential current = em.find(IdentityCredential.class, userId, LockModeType.PESSIMISTIC_READ);
        if (current == null || !current.isEnabled() || !current.getUsername().equals(username)
                || !current.getPasswordHash().equals(passwordHash)) {
            return false;
        }
        em.persist(new IdentitySession(tokenHash, user, audience, expiresAt));
        em.flush();
        return true;
    }
}
