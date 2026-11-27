package com.melonhub.rbac.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** 软件的动态可见范围查询、模糊搜索与行锁片段。 */
public interface ApplicationRepositoryCustom {
    record SummaryRow(long id, String name, String code, String description, String owner,
                      boolean enabled, Integer rateLimit, Instant createdAt) {}

    record SearchRow(long id, String name, String code, boolean enabled) {}

    boolean lockById(long id);

    List<SummaryRow> findVisibleSummaries(Collection<Long> visibleIds);

    List<SearchRow> searchVisible(String keyword, Collection<Long> visibleIds);
}
