package com.melonhub.rbac.service.ddd.domain.repository;

import com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate;
import com.melonhub.rbac.service.ddd.domain.model.UserMembershipAggregate;
import com.melonhub.rbac.service.ddd.domain.value.DomainVisibility;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 核心 RBAC 限界上下文的领域仓储端口（零 Spring/JPA/Jackson 依赖）。
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

    record DepartmentRecord(long id, String name, String code, Instant createdAt, long memberCount, long roleCount) {}

    record ApplicationRecord(long id, String name, String code, String description, String owner, boolean enabled,
                             Integer rateLimit, Instant createdAt, long roleCount, long permissionCount, long activeKeyCount) {}

    record ApplicationRefRecord(long id, String name, String code, boolean enabled) {}

    record ApiKeyRecord(long id, String name, String prefix, String createdBy, Instant createdAt,
                        Instant expiresAt, Instant lastUsedAt, Instant revokedAt, String status) {}

    record UserRecord(long id, String name, String externalId, String email, boolean enabled,
                      Long activeDepartmentId, long contextVersion, Instant createdAt) {}

    record MembershipPair(long userId, long departmentId) {}

    record DepartmentUserRecord(long id, String name, String externalId, boolean enabled) {}

    record AccessRoleRecord(long departmentId, String departmentName, long applicationId, String applicationName,
                            boolean applicationEnabled, long id, String name, String code, boolean enabled,
                            Instant expiresAt, String grantedBy) {}

    record AccessPermissionRecord(long departmentId, long applicationId, String code) {}

    record RoleRecord(long id, String name, String code, String description, boolean enabled, Instant createdAt,
                      long memberCount, List<Long> parentRoleIds) {}

    record RolePermissionPair(long roleId, long permissionId) {}

    record RoleInheritanceEdge(long childRoleId, long parentRoleId) {}

    record PermissionRecord(long id, String name, String code, String resource, String action,
                            Instant createdAt, long roleCount) {}

    record RoleMemberRecord(long id, String name, String externalId, String email, boolean enabled) {}

    record DomainRolePair(long userId, long roleId, Instant expiresAt) {}

    record AuditEventRecord(long id, String actor, String action, String target, String detail, String sourceIp,
                            Instant createdAt, Long departmentId, String departmentName,
                            Long applicationId, String applicationName) {}

    record DomainOverviewRecord(long applications, long enabledApplications, long departments, long users,
                                long enabledUsers, long departmentMembers, long roles, long permissions,
                                long members, long activeKeys, long activeContexts, long auditLast24h) {}

    record DepartmentCodeId(long id, String code) {}

    record PermissionHolderRecord(long userId, String userName, String externalId, long departmentId, String departmentName,
                                  long roleId, String roleName, String roleCode, Instant expiresAt) {}

    record ExpiringAssignmentRecord(long userId, String userName, String externalId, long roleId, String roleName,
                                    String roleCode, long departmentId, String departmentName, long applicationId,
                                    String applicationName, Instant expiresAt, String grantedBy, Instant grantedAt) {}

    record LapsedAssignmentRecord(long userId, String externalId, long roleId, String roleCode,
                                  long departmentId, long applicationId) {}

    record EntityLabel(String name, String code) {}

    boolean healthy();

    void recordAudit(String actor, String action, String target, Long departmentId, Long applicationId, String detail, String sourceIp);

    boolean departmentExists(long id);

    boolean applicationExists(long id);

    Optional<EntityLabel> departmentLabel(long id);

    Optional<EntityLabel> applicationLabel(long id);

    boolean roleExistsInDomain(long id, long departmentId, long applicationId);

    boolean permissionExistsInDomain(long id, long departmentId, long applicationId);

    long countRolesInDomain(Collection<Long> ids, long departmentId, long applicationId);

    long countPermissionsInDomain(Collection<Long> ids, long departmentId, long applicationId);

    Optional<Long> roleIdByCode(long departmentId, long applicationId, String code);

    Optional<Long> permissionIdByCode(long departmentId, long applicationId, String code);

    List<String> roleCodes(Collection<Long> ids);

    List<String> permissionCodes(Collection<Long> ids);

    List<DepartmentRecord> departments(Collection<Long> visibleDepartments);

    long createDepartment(String name, String code);

    int updateDepartment(long id, String name, String code);

    DomainAuthorizationAggregate.DepartmentUsage departmentUsage(long id);

    int deleteDepartment(long id);

    List<ApplicationRecord> applications(Collection<Long> visibleApplications);

    Optional<ApplicationRefRecord> applicationRow(long id);

    boolean applicationEnabled(long id);

    long createApplication(String name, String code, String description, String owner);

    int updateApplication(long id, String name, String code, String description, String owner);

    int updateApplicationStatus(long id, boolean enabled);

    DomainAuthorizationAggregate.ApplicationUsage applicationUsage(long id);

    int deleteApplication(long id);

    boolean lockApplication(long id);

    int lockedActiveKeyCount(long applicationId);

    List<ApiKeyRecord> applicationKeys(long applicationId);

    Optional<String> applicationKeyName(long applicationId, long keyId);

    long createApplicationKey(long applicationId, String name, String prefix, String hash, String actor, Instant expiresAt);

    int revokeApplicationKey(long applicationId, long keyId);

    int revokeAllApplicationKeys(long applicationId);

    int applicationRateLimit(long id, int fallback);

    int updateApplicationRateLimit(long id, Integer perSecond);

    PageSlice<UserRecord> users(String keyword, Long departmentId, Boolean enabled, Collection<Long> visibleDepartments, int page, int size);

    List<MembershipPair> memberships(Collection<Long> userIds);

    List<DepartmentUserRecord> departmentUsers(long departmentId);

    Optional<UserRecord> user(long id);

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

    List<AccessRoleRecord> accessRoles(long userId);

    List<AccessPermissionRecord> accessPermissions(long userId);

    List<RoleRecord> roles(long departmentId, long applicationId);

    long createRole(long departmentId, long applicationId, String name, String code, String description);

    long createRole(long departmentId, long applicationId, String name, String code, String description, boolean enabled);

    void updateRole(long id, String name, String code, String description);

    int updateRoleStatus(long id, boolean enabled);

    void deleteRole(long id);

    List<PermissionRecord> permissions(long departmentId, long applicationId);

    long createPermission(long departmentId, long applicationId, String name, String code, String resource, String action);

    void updatePermission(long id, String name, String code, String resource, String action);

    void deletePermission(long id);

    List<RolePermissionPair> domainRolePermissionPairs(long departmentId, long applicationId);

    List<Long> rolePermissionIds(long roleId);

    List<Long> lockedRolePermissionIds(long roleId);

    void replaceRolePermissions(long roleId, long departmentId, long applicationId, Collection<Long> permissionIds);

    List<Long> domainRoleIds(long departmentId, long applicationId);

    List<RoleInheritanceEdge> domainRoleInheritanceEdges(long departmentId, long applicationId);

    void replaceRoleParents(long roleId, long departmentId, long applicationId,
                            Collection<Long> existingParentIds, Collection<Long> parentIds);

    boolean lockRole(long roleId);

    Set<Long> lockDomainRolesByIds(long departmentId, long applicationId, Collection<Long> roleIds);

    List<RoleMemberRecord> roleMembers(long roleId);

    List<Long> roleMemberIds(long roleId);

    List<Long> lockedRoleMemberIds(long roleId);

    void addRoleMembers(long roleId, long departmentId, long applicationId, Collection<Long> userIds, String actor);

    void removeRoleMembers(long roleId, Collection<Long> userIds);

    Map<Long, Instant> assignmentExpiries(long userId, long departmentId, long applicationId);

    Map<Long, Instant> lockedAssignmentExpiries(long userId, long departmentId, long applicationId);

    PageSlice<RoleMemberRecord> departmentMembers(long departmentId, String keyword, int page, int size);

    List<DomainRolePair> domainRolePairs(long departmentId, long applicationId, Collection<Long> userIds);

    void grantRole(long userId, long departmentId, long applicationId, long roleId, Instant expiresAt, String actor);

    void removeUserRoles(long userId, Collection<Long> roleIds);

    List<DomainAuthorizationAggregate.AssignedDomainRole> assignedRolesInDomain(long userId, long departmentId, long applicationId);

    List<DomainAuthorizationAggregate.PermissionGrant> permissionGrants(long departmentId, long applicationId, Collection<Long> roleIds);

    PageSlice<AuditEventRecord> auditEvents(AuditQuery query, int page, int size);

    DomainOverviewRecord domainOverview(long departmentId, long applicationId);

    List<DepartmentCodeId> departmentIdsByCodes(Collection<String> codes);

    List<Long> manifestDepartmentIds(long applicationId);

    List<PermissionHolderRecord> permissionHolders(long applicationId, String code, Long departmentId, Collection<Long> visibleDepartments, int limit);

    long permissionHolderCount(long applicationId, String code, Long departmentId, Collection<Long> visibleDepartments);

    List<ExpiringAssignmentRecord> expiringAssignments(Instant before, DomainVisibility visibility, Long applicationId);

    List<LapsedAssignmentRecord> lapsedAssignments(int limit);

    int deleteLapsedAssignment(long userId, long roleId);
}
