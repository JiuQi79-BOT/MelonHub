package com.melonhub.rbac.infrastructure.persistence.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DepartmentRepositoryImpl implements DepartmentRepositoryCustom {
    private final EntityManager em;

    DepartmentRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<SummaryRow> findVisibleSummaries(Collection<Long> visibleIds) {
        var where = new JpaQuery.Where();
        if (visibleIds != null) where.in("d.id", visibleIds);
        return where.bind(em.createQuery(
                        "select d.id as id, d.name as name, d.code as code, d.createdAt as createdAt from Department d"
                                + where.text + " order by d.id", Tuple.class))
                .getResultList().stream()
                .map(t -> new SummaryRow(
                        t.get("id", Long.class), t.get("name", String.class),
                        t.get("code", String.class), t.get("createdAt", Instant.class)))
                .toList();
    }

    @Override
    public List<SearchRow> searchVisible(String keyword, Collection<Long> visibleIds) {
        String pattern = JpaQuery.like(keyword);
        var where = new JpaQuery.Where().and("(d.name like ? escape '!' or d.code like ? escape '!')", pattern, pattern);
        if (visibleIds != null) where.in("d.id", visibleIds);
        return where.bind(em.createQuery(
                        "select d.id as id, d.name as name, d.code as code from Department d" + where.text + " order by d.id",
                        Tuple.class))
                .setMaxResults(5).getResultList().stream()
                .map(t -> new SearchRow(t.get("id", Long.class), t.get("name", String.class), t.get("code", String.class)))
                .toList();
    }

    @Override
    public List<IdentityDepartmentRow> findIdentityDepartments(String keyword, Long parentId, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, pageSize);
        var where = identityWhere(keyword, parentId);
        List<Tuple> rows = where.bind(em.createQuery(
                        "select d.id as id, d.code as code, d.name as name, d.parent.id as parentId from Department d"
                                + where.text + " order by d.id", Tuple.class))
                .setFirstResult((int) Math.min(Integer.MAX_VALUE, (long) (safePage - 1) * safeSize))
                .setMaxResults(safeSize).getResultList();
        if (rows.isEmpty()) return List.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (Tuple row : rows) ids.add(row.get("id", Long.class));
        Set<Long> parents = new LinkedHashSet<>(em.createQuery(
                        "select distinct d.parent.id from Department d where d.parent.id in :ids", Long.class)
                .setParameter("ids", ids).getResultList());
        return rows.stream().map(row -> {
            long id = row.get("id", Long.class);
            return new IdentityDepartmentRow(id, row.get("code", String.class), row.get("name", String.class),
                    row.get("parentId", Long.class), parents.contains(id));
        }).toList();
    }

    @Override
    public long countIdentityDepartments(String keyword, Long parentId) {
        var where = identityWhere(keyword, parentId);
        return where.bind(em.createQuery("select count(d) from Department d" + where.text, Long.class)).getSingleResult();
    }

    private static JpaQuery.Where identityWhere(String keyword, Long parentId) {
        var where = new JpaQuery.Where();
        if (parentId == null) where.and("d.parent is null");
        else where.and("d.parent.id = ?", parentId);
        if (keyword != null && !keyword.isBlank()) {
            String pattern = JpaQuery.like(keyword.strip());
            where.and("(d.name like ? escape '!' or d.code like ? escape '!')", pattern, pattern);
        }
        return where;
    }
}
