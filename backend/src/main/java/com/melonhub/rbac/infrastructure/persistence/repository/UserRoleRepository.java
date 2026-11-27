package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.UserRole;
import com.melonhub.rbac.infrastructure.persistence.entity.UserRoleId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId>, UserRoleRepositoryCustom {
    interface RoleMemberCount {
        long getRoleId();
        long getCount();
    }

    interface RoleExpiry {
        long getRoleId();
        Instant getExpiresAt();
    }

    interface DepartmentAssignment {
        long getDepartmentId();
        long getApplicationId();
        long getRoleId();
        String getRoleCode();
    }

    interface RoleMember {
        long getId();
        String getName();
        String getExternalId();
        String getEmail();
        boolean isEnabled();
    }

    interface DomainRolePair {
        long getUserId();
        long getRoleId();
        Instant getExpiresAt();
    }

    interface DomainUserRole {
        long getId();
        String getName();
        String getCode();
        boolean isEnabled();
        Instant getExpiresAt();
    }

    interface AccessRole {
        long getDepartmentId();
        String getDepartmentName();
        long getApplicationId();
        String getApplicationName();
        boolean isApplicationEnabled();
        long getId();
        String getName();
        String getCode();
        boolean isEnabled();
        Instant getExpiresAt();
        String getGrantedBy();
    }

    interface DirectRoleDomain {
        long getRoleId();
        long getDepartmentId();
        long getApplicationId();
    }

    interface ActorRoleCode {
        long getUserId();
        String getCode();
    }

    interface LapsedAssignment {
        long getUserId();
        String getExternalId();
        long getRoleId();
        String getRoleCode();
        long getDepartmentId();
        long getApplicationId();
    }

    @Query("select ur.role.id as roleId, count(ur) as count from UserRole ur where ur.role.id in :roleIds group by ur.role.id")
    List<RoleMemberCount> countByRoleIds(Collection<Long> roleIds);

    @Query("select count(distinct ur.user.id) from UserRole ur where ur.departmentId = :departmentId and ur.applicationId = :applicationId")
    long countDistinctUsersInDomain(long departmentId, long applicationId);

    @Query("""
            select r.id from UserRole ur join ur.role r
            where ur.user.id = :userId and (ur.expiresAt is null or ur.expiresAt > current_timestamp)
              and r.enabled = true and r.department.id = :departmentId and r.application.id = :applicationId
            order by r.id""")
    List<Long> findActiveAssignedRoleIds(long userId, long departmentId, long applicationId);

    @Query("""
            select ur.role.id as roleId, ur.expiresAt as expiresAt from UserRole ur
            where ur.user.id = :userId and ur.departmentId = :departmentId and ur.applicationId = :applicationId
            order by ur.role.id""")
    List<RoleExpiry> findExpiriesByDomain(long userId, long departmentId, long applicationId);

    @Query("""
            select ur.departmentId as departmentId, ur.applicationId as applicationId,
                   ur.role.id as roleId, ur.role.code as roleCode
            from UserRole ur where ur.user.id = :userId and ur.departmentId in :departmentIds
            order by ur.departmentId, ur.applicationId, ur.role.id""")
    List<DepartmentAssignment> findAssignmentsInDepartments(long userId, Collection<Long> departmentIds);

    @Query("""
            select u.id as id, u.name as name, u.externalId as externalId, u.email as email, u.enabled as enabled
            from UserRole ur join ur.user u where ur.role.id = :roleId order by u.id""")
    List<RoleMember> findRoleMembers(long roleId);

    @Query("select ur.user.id from UserRole ur where ur.role.id = :roleId order by ur.user.id")
    List<Long> findRoleMemberIds(long roleId);

    @Query("""
            select ur.user.id as userId, ur.role.id as roleId, ur.expiresAt as expiresAt from UserRole ur
            where ur.departmentId = :departmentId and ur.applicationId = :applicationId and ur.user.id in :userIds
            order by ur.role.id""")
    List<DomainRolePair> findDomainRolePairs(long departmentId, long applicationId, Collection<Long> userIds);

    @Query("""
            select r.id as id, r.name as name, r.code as code, r.enabled as enabled, ur.expiresAt as expiresAt
            from UserRole ur join ur.role r
            where ur.user.id = :userId and r.department.id = :departmentId and r.application.id = :applicationId
            order by r.id""")
    List<DomainUserRole> findUserRolesInDomain(long userId, long departmentId, long applicationId);

    @Query("""
            select ur.departmentId as departmentId, d.name as departmentName,
                   ur.applicationId as applicationId, a.name as applicationName, a.enabled as applicationEnabled,
                   r.id as id, r.name as name, r.code as code, r.enabled as enabled,
                   ur.expiresAt as expiresAt, ur.grantedBy as grantedBy
            from UserRole ur join ur.role r join ur.department d join ur.application a
            where ur.user.id = :userId order by ur.departmentId, ur.applicationId, r.id""")
    List<AccessRole> findAccessRoles(long userId);

    @Query("""
            select ur.role.id as roleId, ur.departmentId as departmentId, ur.applicationId as applicationId
            from UserRole ur join ur.role r
            where ur.user.id = :userId and r.enabled = true
              and (ur.expiresAt is null or ur.expiresAt > current_timestamp)""")
    List<DirectRoleDomain> findActiveDirectRoles(long userId);

    @Query("""
            select ur.role.id as roleId, ur.departmentId as departmentId, ur.application.id as applicationId
            from UserRole ur join ur.role r join r.application a
            where ur.user.id = :userId and a.code = :applicationCode and a.enabled = true and r.enabled = true
              and (ur.expiresAt is null or ur.expiresAt > current_timestamp)""")
    List<DirectRoleDomain> findActiveIdentityRoles(long userId, String applicationCode);

    @Query("""
            select distinct ur.user.id as userId, r.code as code
            from UserRole ur join ur.role r join r.application a
            where ur.user.id in :userIds and a.code = :applicationCode and a.enabled = true and r.enabled = true
              and ur.departmentId in :departmentIds
              and (ur.expiresAt is null or ur.expiresAt > current_timestamp)
            order by r.code""")
    List<ActorRoleCode> findAuditActorRoles(Collection<Long> userIds, String applicationCode, Collection<Long> departmentIds);

    @Query("select count(ur) from UserRole ur where ur.expiresAt is not null and ur.expiresAt <= :deadline")
    long countExpiringBy(Instant deadline);

    @Query("""
            select u.id as userId, u.externalId as externalId, r.id as roleId, r.code as roleCode,
                   ur.departmentId as departmentId, ur.applicationId as applicationId
            from UserRole ur join ur.user u join ur.role r
            where ur.expiresAt <= current_timestamp order by ur.expiresAt, u.id, r.id""")
    List<LapsedAssignment> findLapsedAssignments(Limit limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRole ur where ur.user.id = :userId and ur.role.id in :roleIds")
    int deleteByUserIdAndRoleIdIn(long userId, Collection<Long> roleIds);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRole ur where ur.user.id = :userId and ur.departmentId in :departmentIds")
    int deleteInDepartments(long userId, Collection<Long> departmentIds);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRole ur where ur.role.id = :roleId and ur.user.id in :userIds")
    int deleteRoleMembers(long roleId, Collection<Long> userIds);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRole ur where ur.user.id = :userId and ur.role.id = :roleId and ur.expiresAt <= current_timestamp")
    int deleteLapsedAssignment(long userId, long roleId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRole ur where ur.user.id = :userId and ur.role.id = :roleId")
    int revokeGrantAny(long userId, long roleId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRole ur where ur.user.id = :userId and ur.role.id = :roleId and ur.grantId = :grantId")
    int revokeGrantExact(long userId, long roleId, String grantId);

    @Transactional
    default int revokeGrant(long userId, long roleId, String grantId) {
        return grantId == null ? revokeGrantAny(userId, roleId) : revokeGrantExact(userId, roleId, grantId);
    }
}
