package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Permission;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
    interface PermissionSummary {
        long getId();
        String getName();
        String getCode();
        String getResource();
        String getAction();
        Instant getCreatedAt();
    }

    interface ApplicationCount {
        long getApplicationId();
        long getCount();
    }

    long countByDepartmentId(long departmentId);

    long countByApplicationId(long applicationId);

    long countByDepartmentIdAndApplicationId(long departmentId, long applicationId);

    boolean existsByIdAndDepartmentIdAndApplicationId(long id, long departmentId, long applicationId);

    long countByDepartmentIdAndApplicationIdAndIdIn(long departmentId, long applicationId, Collection<Long> ids);

    @Query("""
            select p.id as id, p.name as name, p.code as code, p.resource as resource,
                   p.action as action, p.createdAt as createdAt
            from Permission p where p.department.id = :departmentId and p.application.id = :applicationId order by p.id""")
    List<PermissionSummary> findSummariesByDomain(long departmentId, long applicationId);

    @Query("select p.code from Permission p where p.id in :ids order by p.code")
    List<String> findCodesByIdIn(Collection<Long> ids);

    @Query("select p.id from Permission p where p.department.id = :departmentId and p.application.id = :applicationId and p.code = :code")
    Optional<Long> findIdByScopedCode(long departmentId, long applicationId, String code);

    @Query("select distinct p.department.id from Permission p where p.application.id = :applicationId")
    List<Long> findDistinctDepartmentIdsByApplicationId(long applicationId);

    @Query("select p.application.id as applicationId, count(p) as count from Permission p where p.application.id in :applicationIds group by p.application.id")
    List<ApplicationCount> countByApplicationIds(Collection<Long> applicationIds);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Permission p set p.name = :name, p.code = :code, p.resource = :resource, p.action = :action where p.id = :id")
    int updateProfile(long id, String name, String code, String resource, String action);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Permission p where p.id = :id")
    int deleteByIdReturningCount(long id);
}
