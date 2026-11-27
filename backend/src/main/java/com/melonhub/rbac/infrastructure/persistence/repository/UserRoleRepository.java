package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import com.melonhub.rbac.infrastructure.persistence.entity.UserRole;
import com.melonhub.rbac.infrastructure.persistence.entity.UserRoleId;
import com.melonhub.rbac.service.port.security.Visibility;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
    record PermissionHolderRow(long userId, String userName, String externalId, long departmentId, String departmentName,
                               long roleId, String roleName, String roleCode, Instant expiresAt) {}

    record ExpiringAssignmentRow(long userId, String userName, String externalId, long roleId, String roleName,
                                 String roleCode, long departmentId, String departmentName, long applicationId,
                                 String applicationName, Instant expiresAt, String grantedBy, Instant grantedAt) {}

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

    interface DirectHolderRole {
        long getRoleId();
        long getDepartmentId();
    }

    interface InheritanceEdge {
        long getChildRoleId();
        long getParentRoleId();
    }

    interface HolderPair {
        long getDepartmentId();
        long getUserId();
    }

    interface PermissionHolderProjection {
        long getUserId();
        String getUserName();
        String getExternalId();
        long getDepartmentId();
        String getDepartmentName();
        long getRoleId();
        String getRoleName();
        String getRoleCode();
        Instant getExpiresAt();
    }

    interface ExpiringAssignmentProjection {
        long getUserId();
        String getUserName();
        String getExternalId();
        long getRoleId();
        String getRoleName();
        String getRoleCode();
        long getDepartmentId();
        String getDepartmentName();
        long getApplicationId();
        String getApplicationName();
        Instant getExpiresAt();
        String getGrantedBy();
        Instant getGrantedAt();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ur from UserRole ur
            where ur.user.id = :userId and ur.departmentId = :departmentId and ur.applicationId = :applicationId
            order by ur.role.id""")
    List<UserRole> findLockedByUserAndDomain(long userId, long departmentId, long applicationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ur from UserRole ur where ur.role.id = :roleId order by ur.user.id")
    List<UserRole> findLockedByRoleId(long roleId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ur from UserRole ur where ur.id = :id")
    Optional<UserRole> findLockedById(UserRoleId id);

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

    @Query("""
            select distinct r.id as roleId, r.department.id as departmentId
            from RolePermission rp join rp.role r join rp.permission p
            where r.enabled = true and p.application.id = :app and p.code = :code
              and r.department.id = p.department.id
              and (:dept is null or r.department.id = :dept)
              and (:filterVisible = false or r.department.id in :visible)""")
    List<DirectHolderRole> findDirectHolderRoles(long app, String code, Long dept,
                                                 boolean filterVisible, Collection<Long> visible);

    @Query("""
            select ri.id.childRoleId as childRoleId, ri.id.parentRoleId as parentRoleId
            from RoleInheritance ri join ri.childRole c join ri.parentRole p
            where ri.applicationId = :app and ri.departmentId in :depts
              and c.enabled = true and p.enabled = true""")
    List<InheritanceEdge> findActiveInheritanceEdges(long app, Collection<Long> depts);

    @Query("""
            select distinct ur.departmentId as departmentId, u.id as userId
            from UserRole ur join ur.user u join ur.role r join ur.department d
            where ur.role.id in :roleIds and u.enabled = true and r.enabled = true
              and ur.applicationId = :app and (ur.expiresAt is null or ur.expiresAt > current_timestamp)
              and (:dept is null or ur.departmentId = :dept)
              and (:filterVisible = false or ur.departmentId in :visible)""")
    List<HolderPair> findAllDistinctHolderPairs(long app, Collection<Long> roleIds, Long dept,
                                                boolean filterVisible, Collection<Long> visible);

    @Query("""
            select distinct ur.departmentId as departmentId, u.id as userId
            from UserRole ur join ur.user u join ur.role r join ur.department d
            where ur.role.id in :roleIds and u.enabled = true and r.enabled = true
              and ur.applicationId = :app and (ur.expiresAt is null or ur.expiresAt > current_timestamp)
              and (:dept is null or ur.departmentId = :dept)
              and (:filterVisible = false or ur.departmentId in :visible)
            order by ur.departmentId, u.id""")
    List<HolderPair> findLimitedDistinctHolderPairs(long app, Collection<Long> roleIds, Long dept,
                                                    boolean filterVisible, Collection<Long> visible, Limit limit);

    @Query("""
            select u.id as userId, u.name as userName, u.externalId as externalId,
                   ur.departmentId as departmentId, d.name as departmentName,
                   r.id as roleId, r.name as roleName, r.code as roleCode, ur.expiresAt as expiresAt
            from UserRole ur join ur.user u join ur.role r join ur.department d
            where ur.role.id in :roleIds and u.enabled = true and r.enabled = true
              and ur.applicationId = :app and (ur.expiresAt is null or ur.expiresAt > current_timestamp)
              and (:dept is null or ur.departmentId = :dept)
              and (:filterVisible = false or ur.departmentId in :visible)
              and u.id in :userIds
            order by ur.departmentId, u.id, r.id""")
    List<PermissionHolderProjection> findHolderDetailProjections(long app, Collection<Long> roleIds, Long dept,
                                                                 boolean filterVisible, Collection<Long> visible,
                                                                 Collection<Long> userIds);

    @Query("""
            select u.id as userId, u.name as userName, u.externalId as externalId,
                   r.id as roleId, r.name as roleName, r.code as roleCode,
                   ur.departmentId as departmentId, d.name as departmentName,
                   ur.applicationId as applicationId, a.name as applicationName,
                   ur.expiresAt as expiresAt, ur.grantedBy as grantedBy, ur.grantedAt as grantedAt
            from UserRole ur join ur.user u join ur.role r join ur.department d join ur.application a
            where ur.expiresAt is not null and ur.expiresAt <= :deadline
              and (:filterApps = false or ur.applicationId in :appIds)
              and (:filterDepts = false or ur.departmentId in :deptIds)
              and (:applicationId is null or ur.applicationId = :applicationId)
            order by ur.expiresAt""")
    List<ExpiringAssignmentProjection> findExpiringProjections(Instant deadline,
                                                               boolean filterApps, Collection<Long> appIds,
                                                               boolean filterDepts, Collection<Long> deptIds,
                                                               Long applicationId, Limit limit);

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

    @Transactional
    default Map<Long, Instant> findLockedAssignments(long userId, long departmentId, long applicationId) {
        var result = new LinkedHashMap<Long, Instant>();
        for (UserRole ur : findLockedByUserAndDomain(userId, departmentId, applicationId)) {
            result.put(ur.getId().getRoleId(), ur.getExpiresAt());
        }
        return result;
    }

    @Transactional
    default List<Long> findLockedRoleMemberIds(long roleId) {
        return findLockedByRoleId(roleId).stream()
                .map(ur -> ur.getId().getUserId())
                .toList();
    }

    @Transactional
    default void addRoleMembers(long roleId, long departmentId, long applicationId, Collection<Long> userIds,
                                String grantedBy, LongFunction<User> userRef, LongFunction<Role> roleRef) {
        if (userIds.isEmpty()) return;
        Instant now = RepositorySupport.now();
        Role role = roleRef.apply(roleId);
        saveAllAndFlush(userIds.stream()
                .map(userId -> newAssignment(userId, roleId, departmentId, applicationId, null, grantedBy, now,
                        userRef.apply(userId), role))
                .toList());
    }

    @Transactional
    default void grantRole(long userId, long departmentId, long applicationId, long roleId,
                           Instant expiresAt, String grantedBy,
                           LongFunction<User> userRef, LongFunction<Role> roleRef) {
        Instant now = RepositorySupport.now();
        var id = new UserRoleId(userId, roleId);
        UserRole existing = findLockedById(id).orElse(null);
        if (existing == null) {
            saveAndFlush(newAssignment(userId, roleId, departmentId, applicationId, expiresAt, grantedBy, now,
                    userRef.apply(userId), roleRef.apply(roleId)));
        } else {
            existing.setExpiresAt(expiresAt)
                    .setGrantedBy(RepositorySupport.truncate(grantedBy, 100))
                    .setGrantedAt(now)
                    .setGrantId(UUID.randomUUID().toString());
            saveAndFlush(existing);
        }
    }

    default long countPermissionHolders(long applicationId, String permissionCode, Long departmentId,
                                        Collection<Long> visibleDepartments) {
        boolean filterVisible = visibleDepartments != null;
        if (filterVisible && visibleDepartments.isEmpty()) return 0L;
        Collection<Long> safeVisible = filterVisible ? visibleDepartments : RepositorySupport.NO_IDS;
        Set<Long> roleIds = effectiveHolderRoleIds(applicationId, permissionCode, departmentId, filterVisible, safeVisible);
        if (roleIds.isEmpty()) return 0L;
        return findAllDistinctHolderPairs(applicationId, roleIds, departmentId, filterVisible, safeVisible).size();
    }

    default List<PermissionHolderRow> findPermissionHolders(long applicationId, String permissionCode, Long departmentId,
                                                            Collection<Long> visibleDepartments, int limit) {
        boolean filterVisible = visibleDepartments != null;
        if (filterVisible && visibleDepartments.isEmpty()) return List.of();
        Collection<Long> safeVisible = filterVisible ? visibleDepartments : RepositorySupport.NO_IDS;
        Set<Long> roleIds = effectiveHolderRoleIds(applicationId, permissionCode, departmentId, filterVisible, safeVisible);
        if (roleIds.isEmpty()) return List.of();
        var first = findLimitedDistinctHolderPairs(applicationId, roleIds, departmentId, filterVisible, safeVisible,
                Limit.of(limit));
        if (first.isEmpty()) return List.of();
        var pairs = new LinkedHashSet<String>();
        var users = new LinkedHashSet<Long>();
        for (var pair : first) {
            pairs.add(pair.getDepartmentId() + ":" + pair.getUserId());
            users.add(pair.getUserId());
        }
        return findHolderDetailProjections(applicationId, roleIds, departmentId, filterVisible, safeVisible, users).stream()
                .map(p -> new PermissionHolderRow(
                        p.getUserId(), p.getUserName(), p.getExternalId(),
                        p.getDepartmentId(), p.getDepartmentName(),
                        p.getRoleId(), p.getRoleName(), p.getRoleCode(), p.getExpiresAt()))
                .filter(row -> pairs.contains(row.departmentId() + ":" + row.userId()))
                .toList();
    }

    default List<ExpiringAssignmentRow> findExpiringAssignments(Instant deadline, Visibility visible, Long applicationId) {
        boolean filterApps = visible.applications() != null;
        boolean filterDepts = visible.departments() != null;
        if ((filterApps && visible.applications().isEmpty()) || (filterDepts && visible.departments().isEmpty())) {
            return List.of();
        }
        return findExpiringProjections(deadline,
                filterApps, filterApps ? visible.applications() : RepositorySupport.NO_IDS,
                filterDepts, filterDepts ? visible.departments() : RepositorySupport.NO_IDS,
                applicationId, Limit.of(500)).stream()
                .map(p -> new ExpiringAssignmentRow(
                        p.getUserId(), p.getUserName(), p.getExternalId(),
                        p.getRoleId(), p.getRoleName(), p.getRoleCode(),
                        p.getDepartmentId(), p.getDepartmentName(),
                        p.getApplicationId(), p.getApplicationName(),
                        p.getExpiresAt(), p.getGrantedBy(), p.getGrantedAt()))
                .toList();
    }

    /** 先定位直接绑定目标权限码的启用角色，再沿同隔离域启用继承边反向展开所有继承该权限的子孙角色。 */
    private Set<Long> effectiveHolderRoleIds(long app, String code, Long dept,
                                             boolean filterVisible, Collection<Long> safeVisible) {
        var directRows = findDirectHolderRoles(app, code, dept, filterVisible, safeVisible);
        if (directRows.isEmpty()) return Set.of();
        Set<Long> directRoles = new LinkedHashSet<>();
        Set<Long> depts = new LinkedHashSet<>();
        for (var row : directRows) {
            directRoles.add(row.getRoleId());
            depts.add(row.getDepartmentId());
        }
        Map<Long, List<Long>> childrenByParent = new LinkedHashMap<>();
        for (var edge : findActiveInheritanceEdges(app, depts)) {
            childrenByParent.computeIfAbsent(edge.getParentRoleId(), k -> new ArrayList<>())
                    .add(edge.getChildRoleId());
        }
        Set<Long> result = new LinkedHashSet<>();
        var queue = new ArrayDeque<>(directRoles);
        while (!queue.isEmpty()) {
            long roleId = queue.removeFirst();
            if (!result.add(roleId)) continue;
            queue.addAll(childrenByParent.getOrDefault(roleId, List.of()));
        }
        return result;
    }

    private static UserRole newAssignment(long userId, long roleId, long deptId, long appId,
                                          Instant expiresAt, String grantedBy, Instant now,
                                          User user, Role role) {
        return new UserRole()
                .setId(new UserRoleId(userId, roleId))
                .setUser(user)
                .setRole(role)
                .setDepartmentId(deptId)
                .setApplicationId(appId)
                .setExpiresAt(expiresAt)
                .setGrantedBy(RepositorySupport.truncate(grantedBy, 100))
                .setGrantedAt(now)
                .setGrantId(UUID.randomUUID().toString());
    }
}
