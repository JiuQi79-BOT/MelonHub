package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.entity.RoleInheritance;
import com.melonhub.rbac.infrastructure.persistence.entity.RoleInheritanceId;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RoleInheritanceRepositoryImpl implements RoleInheritanceRepositoryCustom {
    private final EntityManager em;

    RoleInheritanceRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public void replaceParents(long childRoleId, long departmentId, long applicationId,
                               Collection<Long> existingParentIds, Collection<Long> parentIds) {
        var existing = new LinkedHashSet<>(existingParentIds);
        var wanted = new LinkedHashSet<>(parentIds);
        var toRemove = new ArrayList<Long>();
        for (long oldParent : existing) {
            if (!wanted.contains(oldParent)) {
                toRemove.add(oldParent);
            }
        }
        if (!toRemove.isEmpty()) {
            em.flush();
            for (long oldParent : toRemove) {
                em.createQuery("delete from RoleInheritance ri where ri.id.childRoleId = :child and ri.id.parentRoleId = :parent")
                        .setParameter("child", childRoleId)
                        .setParameter("parent", oldParent)
                        .executeUpdate();
            }
            em.clear();
        }
        boolean inserted = false;
        Role child = em.getReference(Role.class, childRoleId);
        for (long parentId : wanted) {
            if (existing.contains(parentId)) {
                continue;
            }
            em.persist(new RoleInheritance()
                    .setId(new RoleInheritanceId(childRoleId, parentId))
                    .setChildRole(child)
                    .setParentRole(em.getReference(Role.class, parentId))
                    .setDepartmentId(departmentId)
                    .setApplicationId(applicationId));
            inserted = true;
        }
        if (inserted) {
            em.flush();
        }
    }
}
