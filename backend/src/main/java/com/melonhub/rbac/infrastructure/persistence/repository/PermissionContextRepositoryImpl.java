package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.PermissionContext;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class PermissionContextRepositoryImpl implements PermissionContextRepositoryCustom {
    private final EntityManager em;

    PermissionContextRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public void persistContext(String hash, long userId, long departmentId, long applicationId,
                               long contextVersion, Instant expiresAt) {
        em.persist(new PermissionContext()
                .setId(hash)
                .setUser(em.getReference(User.class, userId))
                .setDepartmentId(departmentId)
                .setApplicationId(applicationId)
                .setContextVersion(contextVersion)
                .setExpiresAt(expiresAt));
        em.flush();
    }
}
