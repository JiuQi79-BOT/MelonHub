package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.ContextRole;
import com.melonhub.rbac.infrastructure.persistence.entity.ContextRoleId;
import com.melonhub.rbac.infrastructure.persistence.entity.PermissionContext;
import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ContextRoleRepositoryImpl implements ContextRoleRepositoryCustom {
    private final EntityManager em;

    ContextRoleRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public void activateRoles(String contextId, Collection<Long> roleIds) {
        if (roleIds.isEmpty()) return;
        var context = em.getReference(PermissionContext.class, contextId);
        for (long roleId : roleIds) {
            em.persist(new ContextRole()
                    .setId(new ContextRoleId(contextId, roleId))
                    .setContext(context)
                    .setRole(em.getReference(Role.class, roleId)));
        }
        em.flush();
    }
}
