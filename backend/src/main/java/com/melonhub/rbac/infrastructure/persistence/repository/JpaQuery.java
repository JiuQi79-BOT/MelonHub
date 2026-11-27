package com.melonhub.rbac.infrastructure.persistence.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Spring Data JPA 自定义仓储片段内部共用的动态 JPQL 拼装、分页、加锁读与时间截断工具。
 */
final class JpaQuery {
    private JpaQuery() {}

    /** 以 AND 拼接动态条件，子句中的 "?" 依次绑定为命名参数。 */
    static final class Where {
        final StringBuilder text = new StringBuilder(" where 1=1");
        final Map<String, Object> params = new LinkedHashMap<>();

        Where and(String clause, Object... values) {
            var out = new StringBuilder();
            int next = 0;
            for (char c : clause.toCharArray()) {
                if (c != '?') {
                    out.append(c);
                    continue;
                }
                String name = "p" + params.size();
                params.put(name, values[next++]);
                out.append(':').append(name);
            }
            text.append(" and ").append(out);
            return this;
        }

        /** path in (values)；空集合安全转为恒假条件 1=0。 */
        Where in(String path, Collection<?> values) {
            return values.isEmpty() ? and("1=0") : and(path + " in ?", values);
        }

        <T> TypedQuery<T> bind(TypedQuery<T> query) {
            params.forEach(query::setParameter);
            return query;
        }
    }

    static <T> Optional<T> first(TypedQuery<T> query) {
        var rows = query.setMaxResults(1).getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    static <T> List<T> lockedList(EntityManager em, TypedQuery<T> query, LockModeType mode) {
        em.flush();
        em.clear();
        return query.setLockMode(mode).getResultList();
    }

    static <T> Page<T> page(EntityManager em, String select, String from, Where where, String order,
                            int page, int size, Function<Tuple, T> mapper) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        var pageable = PageRequest.of(safePage - 1, safeSize);
        long total = where.bind(em.createQuery("select count(*) " + from + where.text, Long.class)).getSingleResult();
        if (total == 0) return new PageImpl<>(List.of(), pageable, 0);
        var query = where.bind(em.createQuery(select + " " + from + where.text + " order by " + order, Tuple.class));
        query.setFirstResult((int) Math.min(Integer.MAX_VALUE, (long) (safePage - 1) * safeSize)).setMaxResults(safeSize);
        return new PageImpl<>(query.getResultList().stream().map(mapper).toList(), pageable, total);
    }

    /** 构造用于 {@code escape '!'} 的 LIKE 模糊匹配模式。 */
    static String like(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    /** MySQL TIMESTAMP 按整秒存储，写入前截断至秒以保证内存与库中时间一致。 */
    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    static long num(Object value) {
        return ((Number) value).longValue();
    }

    static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
