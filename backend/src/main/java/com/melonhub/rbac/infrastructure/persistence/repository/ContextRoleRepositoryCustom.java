package com.melonhub.rbac.infrastructure.persistence.repository;

import java.util.Collection;

/** 上下文激活角色批量插入片段。 */
public interface ContextRoleRepositoryCustom {
    void activateRoles(String contextId, Collection<Long> roleIds);
}
