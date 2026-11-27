package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Application;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ApplicationRepositoryImpl implements ApplicationRepositoryCustom {
    private final EntityManager em;

    ApplicationRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public boolean lockById(long id) {
        var query = em.createQuery("select a from Application a where a.id = :id", Application.class)
                .setParameter("id", id);
        return !JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_WRITE).isEmpty();
    }

    @Override
    public List<SummaryRow> findVisibleSummaries(Collection<Long> visibleIds) {
        var where = new JpaQuery.Where();
        if (visibleIds != null) where.in("a.id", visibleIds);
        return where.bind(em.createQuery("""
                        select a.id as id, a.name as name, a.code as code, a.description as description, a.owner as owner,
                               a.enabled as enabled, a.rateLimit as rateLimit, a.createdAt as createdAt
                        from Application a""" + where.text + " order by a.id", Tuple.class))
                .getResultList().stream()
                .map(t -> new SummaryRow(
                        t.get("id", Long.class), t.get("name", String.class), t.get("code", String.class),
                        t.get("description", String.class), t.get("owner", String.class),
                        Boolean.TRUE.equals(t.get("enabled")), t.get("rateLimit", Integer.class),
                        t.get("createdAt", Instant.class)))
                .toList();
    }

    @Override
    public List<SearchRow> searchVisible(String keyword, Collection<Long> visibleIds) {
        String pattern = JpaQuery.like(keyword);
        var where = new JpaQuery.Where().and("(a.name like ? escape '!' or a.code like ? escape '!')", pattern, pattern);
        if (visibleIds != null) where.in("a.id", visibleIds);
        return where.bind(em.createQuery(
                        "select a.id as id, a.name as name, a.code as code, a.enabled as enabled from Application a"
                                + where.text + " order by a.id", Tuple.class))
                .setMaxResults(5).getResultList().stream()
                .map(t -> new SearchRow(
                        t.get("id", Long.class), t.get("name", String.class), t.get("code", String.class),
                        Boolean.TRUE.equals(t.get("enabled"))))
                .toList();
    }
}
