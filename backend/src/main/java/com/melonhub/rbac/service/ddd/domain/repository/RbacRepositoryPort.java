package com.melonhub.rbac.service.ddd.domain.repository;

import com.melonhub.rbac.service.ddd.domain.model.UserMembershipAggregate;
import com.melonhub.rbac.service.ddd.domain.value.DomainVisibility;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 核心 RBAC 限界上下文的领域仓储端口（零 Spring/JPA 依赖）。
 */
public interface RbacRepositoryPort {
    record AuditQuery(
            Long departmentId,
            Long applicationId,
            DomainVisibility visibility,
            String actor,
            String action,
            String keyword,
            Instant from,
            Instant to
    ) {}

    boolean healthy();

    void recordAudit(String actor, String action, String target, Long departmentId, Long applicationId, String detail, String sourceIp);

    boolean departmentExists(long id);

    boolean applicationExists(long id);

    Optional<String[]> entityLabel(String table, long id);

    boolean scopedExists(String table, long id, long departmentId, long applicationId);

    long scopedCount(String table, Collection<Long> ids, long departmentId, long applicationId);

    Optional<Long> scopedIdByCode(String table, long departmentId, long applicationId, String code);

    List<String> codes(String table, Collection<Long> ids);

    List<Map<String, Object>> departments(Collection<Long> visibleDepartments);

    long createDepartment(String name, String code);

    int updateDepartment(long id, String name, String code);

    long[] departmentUsage(long id);

    int deleteDepartment(long id);

    List<Map<String, Object>> applications(Collection<Long> visibleApplications);

    Optional<Map<String, Object>> applicationRow(long id);

    boolean applicationEnabled(long id);

    long createApplication(String name, String code, String description, String owner);

    int updateApplication(long id, String name, String code, String description, String owner);

    int updateApplicationStatus(long id, boolean enabled);

    long[] applicationUsage(long id);

    int deleteApplication(long id);

    boolean lockApplication(long id);

    int lockedActiveKeyCount(long applicationId);

    List<Map<String, Object>> applicationKeys(long applicationId);

    Optional<String> applicationKeyName(long applicationId, long keyId);

    long createApplicationKey(long applicationId, String name, String prefix, String hash, String actor, Instant expiresAt);

    int revokeApplicationKey(long applicationId, long keyId);

    int revokeAllApplicationKeys(long applicationId);

    int updateApplicationRateLimit(long id, Integer perSecond);

    PageSlice users(String keyword, Long departmentId, Boolean enabled, Collection<Long> visibleDepartments, int page, int size);

    List<Map<String, Object>> memberships(Collection<Long> userIds);

    List<Map<String, Object>> departmentUsers(long departmentId);

    Optional<Map<String, Object>> user(long id);

    Optional<String> lockUser(long id);

    Optional<Long> lockUserByExternalId(String externalId);

    Optional<Long> userIdByExternalId(String externalId);

    Set<Long> lockUsers(Collection<Long> ids);

    long createUser(String name, String externalId, String email);

    void updateUser(long id, String name, String externalId, String email, boolean bumpVersion);

    void updateUserProfile(long id, String name, String email);

    void updateUserStatus(long id, boolean enabled);

    void invalidateUser(long id);

    void deleteUser(long id);

    List<Long> userDepartments(long userId);

    List<Long> lockedUserDepartments(long userId);

    boolean isMember(long userId, long departmentId);

    boolean isLockedMember(long userId, long departmentId);

    long lockedMembersCount(Collection<Long> userIds, long departmentId);

    void addMembership(long userId, long departmentId);

    void removeMembership(long userId, long departmentId);

    void clearInactiveDepartment(long userId);

    List<UserMembershipAggregate.DomainGrant> userRoleAssignmentsInDepartments(long userId, Collection<Long> departmentIds);

    void removeUserRolesInDepartments(long userId, Collection<Long> departmentIds);

    List<Map<String, Object>> accessRoles(long userId);

    List<Map<String, Object>> accessPermissions(long userId);

    List<Map<String, Object>> roles(long departmentId, long applicationId);

    long createRole(long departmentId, long applicationId, String name, String code, String description);

    long createRole(long departmentId, long applicationId, String name, String code, String description, boolean enabled);

    int updateRole(long id, String name, String code, String description);

    int updateRoleStatus(long id, boolean enabled);

    void deleteRole(long id);

    List<Map<String, Object>> permissions(long departmentId, long applicationId);

    long createPermission(long departmentId, long applicationId, String name, String code, String resource, String action);

    int updatePermission(long id, String name, String code, String resource, String action);

    void deletePermission(long id);

    List<Map<String, Object>> domainRolePermissionPairs(long departmentId, long applicationId);

    List<Long> rolePermissionIds(long roleId);

    List<Long> lockedRolePermissionIds(long roleId);

    void replaceRolePermissions(long roleId, long departmentId, long applicationId, Collection<Long> permissionIds);

    List<Map<String, Object>> domainRoleInheritancePairs(long departmentId, long applicationId);
    List<Long> roleParentIds(long roleId, long departmentId, long applicationId);
    List<Long> inheritedRoleIds(Collection<Long> roleIds, long departmentId, long applicationId);
    void replaceRoleParents(long roleId, long departmentId, long applicationId, Collection<Long> parentIds);

    boolean lockRole(long roleId);

    List<Map<String, Object>> roleMembers(long roleId);

    List<Long> roleMemberIds(long roleId);

    List<Long> lockedRoleMemberIds(long roleId);

    void addRoleMembers(long roleId, long departmentId, long applicationId, Collection<Long> userIds, String actor);

    void removeRoleMembers(long roleId, Collection<Long> userIds);

    Map<Long, Object> assignmentExpiryViews(long userId, long departmentId, long applicationId);

    Map<Long, Instant> lockedAssignmentExpiries(long userId, long departmentId, long applicationId);

    PageSlice departmentMembers(long departmentId, String keyword, int page, int size);

    List<Map<String, Object>> domainRolePairs(long departmentId, long applicationId, Collection<Long> userIds);

    void grantRole(long userId, long departmentId, long applicationId, long roleId, Instant expiresAt, String actor);

    void removeUserRoles(long userId, Collection<Long> roleIds);

    List<Map<String, Object>> userRolesInDomain(long userId, long departmentId, long applicationId);

    List<com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate.AssignedDomainRole> assignedRolesInDomain(
            long userId, long departmentId, long applicationId);

    List<com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate.PermissionGrant> permissionGrantsTyped(
            Collection<Long> roleIds);

    List<Map<String, Object>> permissionGrantsForRoles(Collection<Long> roleIds);

    PageSlice auditEvents(AuditQuery query, int page, int size);

    Map<String, Object> domainOverview(long departmentId, long applicationId);

    List<Map<String, Object>> departmentIdsByCodes(Collection<String> codes);

    List<Long> manifestDepartmentIds(long applicationId);

    List<Map<String, Object>> permissionHolders(long applicationId, String code, Long departmentId, Collection<Long> visibleDepartments, int limit);

    long permissionHolderCount(long applicationId, String code, Long departmentId, Collection<Long> visibleDepartments);

    List<Map<String, Object>> expiringAssignments(Instant before, DomainVisibility visibility, Long applicationId);

    List<Map<String, Object>> lapsedAssignments(int limit);

    int deleteLapsedAssignment(long userId, long roleId);
}
