package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.PermissionContext;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface PermissionContextRepository
        extends JpaRepository<PermissionContext, String>, PermissionContextRepositoryCustom {

    interface ContextState {
        long getUserId();
        Long getDepartmentId();
        long getApplicationId();
        long getContextVersion();
        Instant getExpiresAt();
        boolean isValid();
    }

    interface ActiveContext {
        String getId();
        long getDepartmentId();
        String getDepartmentName();
        long getApplicationId();
        String getApplicationName();
        Instant getCreatedAt();
        Instant getExpiresAt();
        boolean isValid();
    }

    @Query("""
            select c.user.id as userId, c.departmentId as departmentId, c.applicationId as applicationId,
                   c.contextVersion as contextVersion, c.expiresAt as expiresAt,
                   case when u.enabled = true and a.enabled = true and u.contextVersion = c.contextVersion
                             and u.activeDepartment.id = c.departmentId
                             and exists (select 1 from Membership m where m.user.id = u.id and m.department.id = c.departmentId)
                        then true else false end as valid
            from PermissionContext c join c.user u join c.application a where c.id = :hash""")
    Optional<ContextState> findStateById(String hash);

    @Query("""
            select c.id as id, c.departmentId as departmentId, d.name as departmentName,
                   c.applicationId as applicationId, a.name as applicationName,
                   c.createdAt as createdAt, c.expiresAt as expiresAt,
                   case when u.enabled = true and a.enabled = true and u.contextVersion = c.contextVersion
                             and u.activeDepartment.id = c.departmentId
                        then true else false end as valid
            from PermissionContext c join c.user u join c.department d join c.application a
            where c.user.id = :userId and c.expiresAt > current_timestamp
            order by c.createdAt desc""")
    List<ActiveContext> findActiveByUserId(long userId, Limit limit);

    @Query("select count(c) from PermissionContext c where c.expiresAt > current_timestamp")
    long countActive();

    @Query("""
            select count(c) from PermissionContext c join c.user u join c.application a
            where c.departmentId = :departmentId and c.applicationId = :applicationId
              and c.contextVersion = u.contextVersion and c.departmentId = u.activeDepartment.id
              and c.expiresAt > current_timestamp and u.enabled = true and a.enabled = true""")
    long countActiveInDomain(long departmentId, long applicationId);

    @Query("select c.id from PermissionContext c where c.expiresAt < current_timestamp")
    List<String> findExpiredIds(Limit limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PermissionContext c where c.id in :ids")
    int deleteByIdIn(Collection<String> ids);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PermissionContext c where c.user.id = :userId and (c.expiresAt < current_timestamp or c.contextVersion <> :version)")
    int deleteStaleForUser(long userId, long version);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PermissionContext c where c.id = :hash and c.applicationId = :applicationId")
    int revokeByIdAndApplicationId(String hash, long applicationId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PermissionContext c where c.user.id = :userId and c.departmentId = :departmentId and c.applicationId = :applicationId")
    int revokeDomain(long userId, long departmentId, long applicationId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PermissionContext c where c.departmentId = :departmentId and c.applicationId = :applicationId and c.user.id in :userIds")
    int revokeDomainForUsers(Collection<Long> userIds, long departmentId, long applicationId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PermissionContext c where c.user.id = :userId")
    int revokeByUserId(long userId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PermissionContext c where c.applicationId = :applicationId")
    int clearByApplicationId(long applicationId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PermissionContext c where c.departmentId = :departmentId")
    int clearByDepartmentId(long departmentId);

    @Transactional
    default int purgeExpired(int limit) {
        var ids = findExpiredIds(Limit.of(limit));
        return ids.isEmpty() ? 0 : deleteByIdIn(ids);
    }
}
