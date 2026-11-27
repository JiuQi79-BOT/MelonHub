package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.ContextRole;
import com.melonhub.rbac.infrastructure.persistence.entity.ContextRoleId;
import com.melonhub.rbac.infrastructure.persistence.entity.PermissionContext;
import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import java.util.Collection;
import java.util.List;
import java.util.function.LongFunction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ContextRoleRepository extends JpaRepository<ContextRole, ContextRoleId> {
    interface ContextRoleCount {
        String getContextId();
        long getCount();
    }

    @Query("select cr.role.id from ContextRole cr where cr.context.id = :contextId")
    List<Long> findRoleIdsByContextId(String contextId);

    @Query("select cr.context.id as contextId, count(cr) as count from ContextRole cr where cr.context.id in :contextIds group by cr.context.id")
    List<ContextRoleCount> countByContextIds(Collection<String> contextIds);

    @Transactional
    default void activateRoles(String contextId, PermissionContext contextRef,
                               Collection<Long> roleIds, LongFunction<Role> roleRef) {
        if (roleIds.isEmpty()) return;
        saveAllAndFlush(roleIds.stream()
                .map(roleId -> new ContextRole()
                        .setId(new ContextRoleId(contextId, roleId))
                        .setContext(contextRef)
                        .setRole(roleRef.apply(roleId)))
                .toList());
    }
}
