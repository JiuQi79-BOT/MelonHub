package com.melonhub.rbac.infrastructure.persistence.repository;

import java.util.Collection;

/** 角色继承边差集精确主键替换片段。 */
public interface RoleInheritanceRepositoryCustom {
    /** 仅按精确联合主键删旧插新，避免范围 DELETE 触发间隙锁/supremum 锁。 */
    void replaceParents(long childRoleId, long departmentId, long applicationId,
                        Collection<Long> existingParentIds, Collection<Long> parentIds);
}
