package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.service.port.security.Visibility;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    record SearchRow(long id, String name, String code, long departmentId, String departmentName,
                     long applicationId, String applicationName) {}

    interface RoleSummary {
        long getId();
        String getName();
        String getCode();
        String getDescription();
        boolean isEnabled();
        Instant getCreatedAt();
    }

    interface EffectiveRole {
        long getId();
        String getName();
        String getCode();
        Instant getExpiresAt();
    }

    interface GroupCount {
        long getGroupId();
        long getCount();
    }

    interface SearchProjection {
        long getId();
        String getName();
        String getCode();
        long getDepartmentId();
        String getDepartmentName();
        long getApplicationId();
        String getApplicationName();
    }

    long countByDepartmentId(long departmentId);

    long countByApplicationId(long applicationId);

    long countByDepartmentIdAndApplicationId(long departmentId, long applicationId);

    boolean existsByIdAndDepartmentIdAndApplicationId(long id, long departmentId, long applicationId);

    long countByDepartmentIdAndApplicationIdAndIdIn(long departmentId, long applicationId, Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.id = :roleId")
    Optional<Role> findLockedById(long roleId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.id in :ids order by r.id")
    List<Role> findLockedByIdInOrderByIdAsc(Collection<Long> ids);

    @Query("""
            select r.id as id, r.name as name, r.code as code, r.description as description,
                   r.enabled as enabled, r.createdAt as createdAt
            from Role r where r.department.id = :departmentId and r.application.id = :applicationId order by r.id""")
    List<RoleSummary> findSummariesByDomain(long departmentId, long applicationId);

    @Query("select r.id from Role r where r.department.id = :departmentId and r.application.id = :applicationId order by r.id")
    List<Long> findIdsByDomain(long departmentId, long applicationId);

    @Query("select r.code from Role r where r.id in :ids order by r.code")
    List<String> findCodesByIdIn(Collection<Long> ids);

    @Query("select distinct r.code from Role r where r.id in :ids and r.enabled = true order by r.code")
    List<String> findActiveCodesByIdIn(Collection<Long> ids);

    @Query("select r.id from Role r where r.department.id = :departmentId and r.application.id = :applicationId and r.code = :code")
    Optional<Long> findIdByScopedCode(long departmentId, long applicationId, String code);

    @Query("select distinct r.department.id from Role r where r.application.id = :applicationId")
    List<Long> findDistinctDepartmentIdsByApplicationId(long applicationId);

    @Query("select r.department.id as groupId, count(r) as count from Role r where r.department.id in :departmentIds group by r.department.id")
    List<GroupCount> countByDepartmentIds(Collection<Long> departmentIds);

    @Query("select r.application.id as groupId, count(r) as count from Role r where r.application.id in :applicationIds group by r.application.id")
    List<GroupCount> countByApplicationIds(Collection<Long> applicationIds);

    @Query("""
            select r.id as id, r.name as name, r.code as code, ur.expiresAt as expiresAt
            from Role r, UserRole ur
            where ur.role = r and ur.user.id = :userId and r.id in :roleIds
              and r.enabled = true
              and r.department.id = :departmentId and r.application.id = :applicationId
              and (ur.expiresAt is null or ur.expiresAt > current_timestamp)
            order by r.id""")
    List<EffectiveRole> findEffectiveRoles(long userId, Collection<Long> roleIds, long departmentId, long applicationId);

    @Query("""
            select r.id as id, r.name as name, r.code as code,
                   d.id as departmentId, d.name as departmentName,
                   a.id as applicationId, a.name as applicationName
            from Role r join r.department d join r.application a
            where (r.name like :pattern escape '!' or r.code like :pattern escape '!')
              and (:filterApps = false or a.id in :appIds)
              and (:filterDepts = false or d.id in :deptIds)
            order by r.id""")
    List<SearchProjection> searchProjections(String pattern, boolean filterApps, Collection<Long> appIds,
                                             boolean filterDepts, Collection<Long> deptIds, Limit limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Role r set r.name = :name, r.code = :code, r.description = :description where r.id = :id")
    int updateProfile(long id, String name, String code, String description);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Role r set r.enabled = :enabled where r.id = :id")
    int updateStatus(long id, boolean enabled);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Role r where r.id = :id")
    int deleteByIdReturningCount(long id);

    @Transactional
    default boolean lockById(long roleId) {
        return findLockedById(roleId).isPresent();
    }

    /**
     * 仅按聚簇主键 {@code PRIMARY(id)} 升序锁定指定角色集合（不触碰二级索引，且作为写事务首条语句避免过早开启 ReadView），
     * 返回当前已提交且属于目标隔离域的角色 ID 集合。
     */
    @Transactional
    default Set<Long> lockDomainRolesByIds(long departmentId, long applicationId, Collection<Long> roleIds) {
        var ids = new TreeSet<>(roleIds);
        if (ids.isEmpty()) return Set.of();
        var domainRoleIds = new LinkedHashSet<Long>();
        for (Role locked : findLockedByIdInOrderByIdAsc(ids)) {
            if (locked.getDepartment().getId().equals(departmentId)
                    && locked.getApplication().getId().equals(applicationId)) {
                domainRoleIds.add(locked.getId());
            }
        }
        return domainRoleIds;
    }

    default List<SearchRow> searchVisible(String keyword, Visibility visible) {
        boolean filterApps = visible.applications() != null;
        boolean filterDepts = visible.departments() != null;
        if ((filterApps && visible.applications().isEmpty()) || (filterDepts && visible.departments().isEmpty())) {
            return List.of();
        }
        return searchProjections(RepositorySupport.like(keyword),
                filterApps, filterApps ? visible.applications() : RepositorySupport.NO_IDS,
                filterDepts, filterDepts ? visible.departments() : RepositorySupport.NO_IDS,
                Limit.of(5)).stream()
                .map(p -> new SearchRow(p.getId(), p.getName(), p.getCode(),
                        p.getDepartmentId(), p.getDepartmentName(),
                        p.getApplicationId(), p.getApplicationName()))
                .toList();
    }
}
