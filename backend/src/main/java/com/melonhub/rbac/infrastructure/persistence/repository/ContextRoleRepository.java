package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.ContextRole;
import com.melonhub.rbac.infrastructure.persistence.entity.ContextRoleId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContextRoleRepository extends JpaRepository<ContextRole, ContextRoleId>, ContextRoleRepositoryCustom {
    interface ContextRoleCount {
        String getContextId();
        long getCount();
    }

    @Query("select cr.role.id from ContextRole cr where cr.context.id = :contextId")
    List<Long> findRoleIdsByContextId(String contextId);

    @Query("select cr.context.id as contextId, count(cr) as count from ContextRole cr where cr.context.id in :contextIds group by cr.context.id")
    List<ContextRoleCount> countByContextIds(Collection<String> contextIds);
}
