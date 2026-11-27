package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.ApplicationKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ApplicationKeyRepositoryImpl implements ApplicationKeyRepositoryCustom {
    private final EntityManager em;

    ApplicationKeyRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public long countLockedActive(long applicationId) {
        var query = em.createQuery("""
                select k from ApplicationKey k where k.application.id = :app and k.revokedAt is null
                and (k.expiresAt is null or k.expiresAt > current_timestamp)""", ApplicationKey.class)
                .setParameter("app", applicationId);
        return JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_READ).size();
    }
}
