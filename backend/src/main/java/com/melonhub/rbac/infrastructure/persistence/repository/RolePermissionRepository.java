package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Permission;
import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.entity.RolePermission;
import com.melonhub.rbac.infrastructure.persistence.entity.RolePermissionId;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.function.LongFunction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {
    interface RolePermissionPair {
        long getRoleId();
        long getPermissionId();
    }

    interface PermissionRoleCount {
        long getPermissionId();
        long getCount();
    }

    interface GrantProjection {
        long getId();
        String getCode();
        String getName();
        String getResource();
        String getAction();
        long getRoleId();
    }

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select rp from RolePermission rp where rp.role.id = :roleId")
    List<RolePermission> findLockedByRoleId(long roleId);

    @Query("""
            select rp.role.id as roleId, rp.permission.id as permissionId from RolePermission rp
            where rp.departmentId = :departmentId and rp.applicationId = :applicationId order by rp.permission.id""")
    List<RolePermissionPair> findPairsByDomain(long departmentId, long applicationId);

    @Query("select rp.permission.id from RolePermission rp where rp.role.id = :roleId")
    List<Long> findPermissionIdsByRoleId(long roleId);

    @Query("select rp.permission.id as permissionId, count(rp) as count from RolePermission rp where rp.permission.id in :permissionIds group by rp.permission.id")
    List<PermissionRoleCount> countRolesByPermissionIds(Collection<Long> permissionIds);

    @Query("""
            select distinct p.code from RolePermission rp join rp.permission p join rp.role r
            where rp.role.id in :roleIds and r.enabled = true order by p.code""")
    List<String> findActivePermissionCodesByRoleIds(Collection<Long> roleIds);

    @Query("""
            select distinct p.code from RolePermission rp join rp.permission p join rp.role r
            where rp.role.id in :roleIds and r.enabled = true
              and p.department.id = :departmentId and p.application.id = :applicationId
            order by p.code""")
    List<String> findActivePermissionCodesInDomain(Collection<Long> roleIds, long departmentId, long applicationId);

    @Query("""
            select distinct p.department.id from RolePermission rp join rp.permission p join rp.role r
            where rp.role.id in :roleIds and r.enabled = true and p.code = :permissionCode order by p.department.id""")
    List<Long> findDepartmentIdsWithPermission(Collection<Long> roleIds, String permissionCode);

    @Query("""
            select p.id as id, p.code as code, p.name as name, p.resource as resource,
                   p.action as action, rp.role.id as roleId
            from RolePermission rp join rp.permission p join rp.role r
            where rp.role.id in :roleIds and r.enabled = true order by p.code, rp.role.id""")
    List<GrantProjection> findGrantsByRoleIds(Collection<Long> roleIds);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RolePermission rp where rp.role.id = :roleId")
    int deleteByRoleId(long roleId);

    @Transactional
    default List<Long> findLockedPermissionIds(long roleId) {
        return findLockedByRoleId(roleId).stream()
                .map(rp -> rp.getId().getPermissionId())
                .toList();
    }

    @Transactional
    default void replaceRolePermissions(long roleId, long departmentId, long applicationId,
                                        Collection<Long> permissionIds,
                                        LongFunction<Role> roleRef, LongFunction<Permission> permissionRef) {
        deleteByRoleId(roleId);
        if (permissionIds.isEmpty()) return;
        Role owner = roleRef.apply(roleId);
        saveAllAndFlush(permissionIds.stream()
                .map(permissionId -> new RolePermission()
                        .setId(new RolePermissionId(roleId, permissionId))
                        .setRole(owner)
                        .setPermission(permissionRef.apply(permissionId))
                        .setDepartmentId(departmentId)
                        .setApplicationId(applicationId))
                .toList());
    }
}
