package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Department;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {
    record SummaryRow(long id, String name, String code, Instant createdAt) {}

    record SearchRow(long id, String name, String code) {}

    record IdentityDepartmentRow(long id, String code, String name, Long parentId, boolean hasChildren) {}

    interface Label {
        String getName();
        String getCode();
    }

    interface CodeId {
        long getId();
        String getCode();
    }

    interface SummaryProjection {
        long getId();
        String getName();
        String getCode();
        Instant getCreatedAt();
    }

    interface SearchProjection {
        long getId();
        String getName();
        String getCode();
    }

    interface IdentityDepartmentProjection {
        long getId();
        String getCode();
        String getName();
        Long getParentId();
    }

    long countByIdIn(Collection<Long> ids);

    @Query("select d.name as name, d.code as code from Department d where d.id = :id")
    Optional<Label> findLabelById(long id);

    @Query("select d.id as id, d.code as code from Department d where d.code in :codes")
    List<CodeId> findCodeIdsByCodeIn(Collection<String> codes);

    @Query("""
            select d.id as id, d.name as name, d.code as code, d.createdAt as createdAt
            from Department d
            where (:filterVisible = false or d.id in :visibleIds)
            order by d.id""")
    List<SummaryProjection> findSummaryProjections(boolean filterVisible, Collection<Long> visibleIds);

    @Query("""
            select d.id as id, d.name as name, d.code as code
            from Department d
            where (d.name like :pattern escape '!' or d.code like :pattern escape '!')
              and (:filterVisible = false or d.id in :visibleIds)
            order by d.id""")
    List<SearchProjection> searchProjections(String pattern, boolean filterVisible, Collection<Long> visibleIds, Limit limit);

    @Query("""
            select d.id as id, d.code as code, d.name as name, d.parent.id as parentId
            from Department d
            where ((:parentId is null and d.parent is null) or (:parentId is not null and d.parent.id = :parentId))
              and (:pattern is null or d.name like :pattern escape '!' or d.code like :pattern escape '!')
            order by d.id""")
    List<IdentityDepartmentProjection> findIdentityDepartmentProjections(Long parentId, String pattern, Pageable pageable);

    @Query("select distinct d.parent.id from Department d where d.parent.id in :ids")
    List<Long> findParentIdsIn(Collection<Long> ids);

    @Query("""
            select count(d) from Department d
            where ((:parentId is null and d.parent is null) or (:parentId is not null and d.parent.id = :parentId))
              and (:pattern is null or d.name like :pattern escape '!' or d.code like :pattern escape '!')""")
    long countIdentityDepartmentRows(Long parentId, String pattern);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Department d set d.name = :name, d.code = :code where d.id = :id")
    int updateProfile(long id, String name, String code);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Department d where d.id = :id")
    int deleteByIdReturningCount(long id);

    default List<SummaryRow> findVisibleSummaries(Collection<Long> visibleIds) {
        boolean filter = visibleIds != null;
        if (filter && visibleIds.isEmpty()) return List.of();
        return findSummaryProjections(filter, filter ? visibleIds : RepositorySupport.NO_IDS).stream()
                .map(p -> new SummaryRow(p.getId(), p.getName(), p.getCode(), p.getCreatedAt()))
                .toList();
    }

    default List<SearchRow> searchVisible(String keyword, Collection<Long> visibleIds) {
        boolean filter = visibleIds != null;
        if (filter && visibleIds.isEmpty()) return List.of();
        return searchProjections(RepositorySupport.like(keyword), filter,
                filter ? visibleIds : RepositorySupport.NO_IDS, Limit.of(5)).stream()
                .map(p -> new SearchRow(p.getId(), p.getName(), p.getCode()))
                .toList();
    }

    default List<IdentityDepartmentRow> findIdentityDepartments(String keyword, Long parentId, int page, int pageSize) {
        var rows = findIdentityDepartmentProjections(parentId, RepositorySupport.likeOrNull(keyword),
                RepositorySupport.page(page, pageSize));
        if (rows.isEmpty()) return List.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (var row : rows) ids.add(row.getId());
        Set<Long> parents = new LinkedHashSet<>(findParentIdsIn(ids));
        return rows.stream()
                .map(row -> new IdentityDepartmentRow(
                        row.getId(), row.getCode(), row.getName(), row.getParentId(), parents.contains(row.getId())))
                .toList();
    }

    default long countIdentityDepartments(String keyword, Long parentId) {
        return countIdentityDepartmentRows(parentId, RepositorySupport.likeOrNull(keyword));
    }
}
