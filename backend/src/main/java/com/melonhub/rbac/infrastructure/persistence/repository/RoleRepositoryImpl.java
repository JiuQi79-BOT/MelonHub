package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.service.port.security.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RoleRepositoryImpl implements RoleRepositoryCustom {
    private final EntityManager em;

    RoleRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public boolean lockById(long roleId) {
        var query = em.createQuery("select r from Role r where r.id = :role", Role.class).setParameter("role", roleId);
        return !JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_WRITE).isEmpty();
    }

    @Override
    public Set<Long> lockDomainRolesByIds(long departmentId, long applicationId, Collection<Long> roleIds) {
        var ids = new TreeSet<>(roleIds);
        if (ids.isEmpty()) {
            return Set.of();
        }
        var query = em.createQuery("select r from Role r where r.id in :ids order by r.id", Role.class)
                .setParameter("ids", ids);
        var domainRoleIds = new LinkedHashSet<Long>();
        for (Role locked : JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_WRITE)) {
            if (locked.getDepartment().getId().equals(departmentId)
                    && locked.getApplication().getId().equals(applicationId)) {
                domainRoleIds.add(locked.getId());
            }
        }
        return domainRoleIds;
    }

    @Override
    public List<SearchRow> searchVisible(String keyword, Visibility visible) {
        String pattern = JpaQuery.like(keyword);
        var where = new JpaQuery.Where().and("(r.name like ? escape '!' or r.code like ? escape '!')", pattern, pattern);
        if (visible.applications() != null) where.in("a.id", visible.applications());
        if (visible.departments() != null) where.in("d.id", visible.departments());
        return where.bind(em.createQuery("""
                        select r.id as id, r.name as name, r.code as code,
                               d.id as departmentId, d.name as departmentName,
                               a.id as applicationId, a.name as applicationName
                        from Role r join r.department d join r.application a""" + where.text + " order by r.id", Tuple.class))
                .setMaxResults(5).getResultList().stream()
                .map(t -> new SearchRow(
                        t.get("id", Long.class), t.get("name", String.class), t.get("code", String.class),
                        t.get("departmentId", Long.class), t.get("departmentName", String.class),
                        t.get("applicationId", Long.class), t.get("applicationName", String.class)))
                .toList();
    }
}
