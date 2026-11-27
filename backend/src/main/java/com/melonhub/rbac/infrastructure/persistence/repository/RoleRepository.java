package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface RoleRepository extends JpaRepository<Role, Long>, RoleRepositoryCustom {
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

    long countByDepartmentId(long departmentId);

    long countByApplicationId(long applicationId);

    long countByDepartmentIdAndApplicationId(long departmentId, long applicationId);

    boolean existsByIdAndDepartmentIdAndApplicationId(long id, long departmentId, long applicationId);

    long countByDepartmentIdAndApplicationIdAndIdIn(long departmentId, long applicationId, Collection<Long> ids);

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
}
