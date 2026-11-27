package com.melonhub.rbac.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** 部门动态可见范围查询、模糊搜索与统一身份部门树分页片段。 */
public interface DepartmentRepositoryCustom {
    record SummaryRow(long id, String name, String code, Instant createdAt) {}

    record SearchRow(long id, String name, String code) {}

    record IdentityDepartmentRow(long id, String code, String name, Long parentId, boolean hasChildren) {}

    List<SummaryRow> findVisibleSummaries(Collection<Long> visibleIds);

    List<SearchRow> searchVisible(String keyword, Collection<Long> visibleIds);

    List<IdentityDepartmentRow> findIdentityDepartments(String keyword, Long parentId, int page, int pageSize);

    long countIdentityDepartments(String keyword, Long parentId);
}
