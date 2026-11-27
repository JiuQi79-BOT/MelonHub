package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Permission;
import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.entity.RolePermission;
import com.melonhub.rbac.infrastructure.persistence.entity.RolePermissionId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RolePermissionRepositoryImpl implements RolePermissionRepositoryCustom {
    private final EntityManager em;

    RolePermissionRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<Long> findLockedPermissionIds(long roleId) {
        var query = em.createQuery("select rp from RolePermission rp where rp.role.id = :role", RolePermission.class)
                .setParameter("role", roleId);
        return JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_READ).stream()
                .map(rp -> rp.getId().getPermissionId()).toList();
    }

    @Override
    public void replaceRolePermissions(long roleId, long departmentId, long applicationId, Collection<Long> permissionIds) {
        em.flush();
        em.createQuery("delete from RolePermission rp where rp.role.id = :role")
                .setParameter("role", roleId).executeUpdate();
        em.clear();
        Role owner = em.getReference(Role.class, roleId);
        for (long permissionId : permissionIds) {
            em.persist(new RolePermission()
                    .setId(new RolePermissionId(roleId, permissionId))
                    .setRole(owner)
                    .setPermission(em.getReference(Permission.class, permissionId))
                    .setDepartmentId(departmentId)
                    .setApplicationId(applicationId));
        }
        em.flush();
    }
}
