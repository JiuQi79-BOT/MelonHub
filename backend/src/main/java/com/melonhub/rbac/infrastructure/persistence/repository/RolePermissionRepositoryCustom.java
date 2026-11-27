package com.melonhub.rbac.infrastructure.persistence.repository;

import java.util.Collection;
import java.util.List;

/** 角色权限关联的加锁读与整集替换片段。 */
public interface RolePermissionRepositoryCustom {
    List<Long> findLockedPermissionIds(long roleId);

    void replaceRolePermissions(long roleId, long departmentId, long applicationId, Collection<Long> permissionIds);
}
