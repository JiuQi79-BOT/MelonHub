package com.melonhub.rbac.infrastructure.persistence.gateway;

import com.melonhub.rbac.infrastructure.persistence.entity.Application;
import com.melonhub.rbac.infrastructure.persistence.entity.ApplicationKey;
import com.melonhub.rbac.infrastructure.persistence.entity.Department;
import com.melonhub.rbac.infrastructure.persistence.entity.Permission;
import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminAccountRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationKeyRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepositoryCustom;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditSealRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.MembershipRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionContextRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleInheritanceRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RolePermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepositoryCustom;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRoleRepository;
import com.melonhub.rbac.service.port.security.Visibility;
import com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate;
import com.melonhub.rbac.service.ddd.domain.model.UserMembershipAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.PageSlice;
import com.melonhub.rbac.service.ddd.domain.repository.RbacRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.value.DomainVisibility;
import com.melonhub.rbac.service.ddd.domain.value.IsolationDomain;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

/** DDD 基础设施适配器：将 {@link RbacRepositoryPort} 桥接到 Spring Data JPA 仓储。 */
@Repository
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class JpaRbacRepositoryAdapter implements RbacRepositoryPort {
    private final DepartmentRepository departments;
    private final ApplicationRepository applications;
    private final ApplicationKeyRepository applicationKeys;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final RolePermissionRepository rolePermissions;
    private final RoleInheritanceRepository roleInheritances;
    private final UserRoleRepository userRoles;
    private final PermissionContextRepository contexts;
    private final AdminAccountRepository adminAccounts;
    private final AuditEventRepository auditEvents;
    private final AuditSealRepository auditSeals;

    public JpaRbacRepositoryAdapter(DepartmentRepository departments, ApplicationRepository applications,
                                    ApplicationKeyRepository applicationKeys, UserRepository users,
                                    MembershipRepository memberships, RoleRepository roles,
                                    PermissionRepository permissions, RolePermissionRepository rolePermissions,
                                    RoleInheritanceRepository roleInheritances, UserRoleRepository userRoles,
                                    PermissionContextRepository contexts, AdminAccountRepository adminAccounts,
                                    AuditEventRepository auditEvents, AuditSealRepository auditSeals) {
        this.departments = departments;
        this.applications = applications;
        this.applicationKeys = applicationKeys;
        this.users = users;
        this.memberships = memberships;
        this.roles = roles;
        this.permissions = permissions;
        this.rolePermissions = rolePermissions;
        this.roleInheritances = roleInheritances;
        this.userRoles = userRoles;
        this.contexts = contexts;
        this.adminAccounts = adminAccounts;
        this.auditEvents = auditEvents;
        this.auditSeals = auditSeals;
    }

    @Override public boolean healthy() { return auditSeals.isDatabaseHealthy(); }

    @Override
    public void recordAudit(String actor, String action, String target, Long departmentId, Long applicationId, String detail, String sourceIp) {
        auditEvents.recordAudit(actor, action, target, departmentId, applicationId, detail, sourceIp);
    }

    @Override public boolean departmentExists(long id) { return departments.existsById(id); }
    @Override public boolean applicationExists(long id) { return applications.existsById(id); }
    @Override public Optional<EntityLabel> departmentLabel(long id) { return departments.findLabelById(id).map(l -> new EntityLabel(l.getName(), l.getCode())); }
    @Override public Optional<EntityLabel> applicationLabel(long id) { return applications.findLabelById(id).map(l -> new EntityLabel(l.getName(), l.getCode())); }

    @Override public boolean roleExistsInDomain(long id, long departmentId, long applicationId) { return roles.existsByIdAndDepartmentIdAndApplicationId(id, departmentId, applicationId); }
    @Override public boolean permissionExistsInDomain(long id, long departmentId, long applicationId) { return permissions.existsByIdAndDepartmentIdAndApplicationId(id, departmentId, applicationId); }

    @Override
    public long countRolesInDomain(Collection<Long> ids, long departmentId, long applicationId) {
        return ids.isEmpty() ? 0 : roles.countByDepartmentIdAndApplicationIdAndIdIn(departmentId, applicationId, ids);
    }

    @Override
    public long countPermissionsInDomain(Collection<Long> ids, long departmentId, long applicationId) {
        return ids.isEmpty() ? 0 : permissions.countByDepartmentIdAndApplicationIdAndIdIn(departmentId, applicationId, ids);
    }

    @Override public Optional<Long> roleIdByCode(long departmentId, long applicationId, String code) { return roles.findIdByScopedCode(departmentId, applicationId, code); }
    @Override public Optional<Long> permissionIdByCode(long departmentId, long applicationId, String code) { return permissions.findIdByScopedCode(departmentId, applicationId, code); }
    @Override public List<String> roleCodes(Collection<Long> ids) { return ids.isEmpty() ? List.of() : roles.findCodesByIdIn(ids); }
    @Override public List<String> permissionCodes(Collection<Long> ids) { return ids.isEmpty() ? List.of() : permissions.findCodesByIdIn(ids); }

    @Override
    public List<DepartmentRecord> departments(Collection<Long> visibleDepartments) {
        var rows = departments.findVisibleSummaries(visibleDepartments);
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(DepartmentRepository.SummaryRow::id).toList();
        var memberCounts = new HashMap<Long, Long>();
        for (var c : memberships.countByDepartmentIds(ids)) memberCounts.put(c.getDepartmentId(), c.getCount());
        var roleCounts = new HashMap<Long, Long>();
        for (var c : roles.countByDepartmentIds(ids)) roleCounts.put(c.getGroupId(), c.getCount());
        return rows.stream().map(d -> new DepartmentRecord(
                d.id(), d.name(), d.code(), d.createdAt(),
                memberCounts.getOrDefault(d.id(), 0L),
                roleCounts.getOrDefault(d.id(), 0L))).toList();
    }

    @Override
    public long createDepartment(String name, String code) {
        return departments.saveAndFlush(new Department().setName(name).setCode(code)).getId();
    }

    @Override public int updateDepartment(long id, String name, String code) { return departments.updateProfile(id, name, code); }

    @Override
    public DomainAuthorizationAggregate.DepartmentUsage departmentUsage(long id) {
        return new DomainAuthorizationAggregate.DepartmentUsage(
                memberships.countByDepartmentId(id),
                roles.countByDepartmentId(id),
                permissions.countByDepartmentId(id),
                adminAccounts.countLimitedToDepartment(id));
    }

    @Override public int deleteDepartment(long id) { return departments.deleteByIdReturningCount(id); }

    @Override
    public List<ApplicationRecord> applications(Collection<Long> visibleApplications) {
        var rows = applications.findVisibleSummaries(visibleApplications);
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(ApplicationRepository.SummaryRow::id).toList();
        var roleCounts = new HashMap<Long, Long>();
        for (var c : roles.countByApplicationIds(ids)) roleCounts.put(c.getGroupId(), c.getCount());
        var permCounts = new HashMap<Long, Long>();
        for (var c : permissions.countByApplicationIds(ids)) permCounts.put(c.getApplicationId(), c.getCount());
        var keyCounts = new HashMap<Long, Long>();
        for (var c : applicationKeys.countActiveByApplicationIds(ids)) keyCounts.put(c.getApplicationId(), c.getCount());
        return rows.stream().map(a -> new ApplicationRecord(
                a.id(), a.name(), a.code(), a.description(), a.owner(), a.enabled(), a.rateLimit(), a.createdAt(),
                roleCounts.getOrDefault(a.id(), 0L),
                permCounts.getOrDefault(a.id(), 0L),
                keyCounts.getOrDefault(a.id(), 0L))).toList();
    }

    @Override
    public Optional<ApplicationRefRecord> applicationRow(long id) {
        return applications.findRefById(id).map(a -> new ApplicationRefRecord(a.getId(), a.getName(), a.getCode(), a.isEnabled()));
    }

    @Override public boolean applicationEnabled(long id) { return applications.existsByIdAndEnabledTrue(id); }

    @Override
    public long createApplication(String name, String code, String description, String owner) {
        return applications.saveAndFlush(new Application().setName(name).setCode(code)
                .setDescription(description).setOwner(owner).setEnabled(true)).getId();
    }

    @Override public int updateApplication(long id, String name, String code, String description, String owner) { return applications.updateProfile(id, name, code, description, owner); }
    @Override public int updateApplicationStatus(long id, boolean enabled) { return applications.updateStatus(id, enabled); }

    @Override
    public DomainAuthorizationAggregate.ApplicationUsage applicationUsage(long id) {
        return new DomainAuthorizationAggregate.ApplicationUsage(
                roles.countByApplicationId(id),
                permissions.countByApplicationId(id));
    }

    @Override public int deleteApplication(long id) { return applications.deleteByIdReturningCount(id); }
    @Override public boolean lockApplication(long id) { return applications.lockById(id); }
    @Override public int lockedActiveKeyCount(long applicationId) { return (int) applicationKeys.countLockedActive(applicationId); }

    @Override
    public List<ApiKeyRecord> applicationKeys(long applicationId) {
        return applicationKeys.findSummariesByApplicationId(applicationId).stream()
                .map(k -> new ApiKeyRecord(k.getId(), k.getName(), k.getPrefix(), k.getCreatedBy(), k.getCreatedAt(),
                        k.getExpiresAt(), k.getLastUsedAt(), k.getRevokedAt(), k.getStatus())).toList();
    }

    @Override public Optional<String> applicationKeyName(long applicationId, long keyId) { return applicationKeys.findNameByIdAndApplicationId(keyId, applicationId); }

    @Override
    public long createApplicationKey(long applicationId, String name, String prefix, String hash, String actor, Instant expiresAt) {
        String createdBy = actor == null ? "" : actor.length() <= 100 ? actor : actor.substring(0, 100);
        return applicationKeys.saveAndFlush(new ApplicationKey()
                .setApplication(applications.getReferenceById(applicationId))
                .setName(name).setKeyPrefix(prefix).setKeyHash(hash)
                .setCreatedBy(createdBy).setExpiresAt(expiresAt)).getId();
    }

    @Override public int revokeApplicationKey(long applicationId, long keyId) { return applicationKeys.revokeOne(keyId, applicationId); }
    @Override public int revokeAllApplicationKeys(long applicationId) { return applicationKeys.revokeAll(applicationId); }
    @Override public int applicationRateLimit(long id, int fallback) { return applications.findRateLimitById(id).orElse(fallback); }
    @Override public int updateApplicationRateLimit(long id, Integer perSecond) { return applications.updateRateLimit(id, perSecond); }

    @Override
    public PageSlice<UserRecord> users(String keyword, Long departmentId, Boolean enabled, Collection<Long> visibleDepartments, int page, int size) {
        var p = users.findPage(keyword, departmentId, enabled, visibleDepartments, page, size);
        return new PageSlice<>(p.getContent().stream().map(JpaRbacRepositoryAdapter::toUserRecord).toList(), p.getTotalElements());
    }

    @Override
    public List<MembershipPair> memberships(Collection<Long> userIds) {
        if (userIds.isEmpty()) return List.of();
        return memberships.findPairsByUserIdIn(userIds).stream()
                .map(m -> new MembershipPair(m.getUserId(), m.getDepartmentId())).toList();
    }

    @Override
    public List<DepartmentUserRecord> departmentUsers(long departmentId) {
        return memberships.findDepartmentUsers(departmentId).stream()
                .map(u -> new DepartmentUserRecord(u.getId(), u.getName(), u.getExternalId(), u.isEnabled())).toList();
    }

    @Override
    public Optional<UserRecord> user(long id) {
        return users.findSummaryById(id).map(u -> new UserRecord(
                u.getId(), u.getName(), u.getExternalId(), u.getEmail(), u.isEnabled(),
                u.getActiveDepartmentId(), u.getContextVersion(), u.getCreatedAt()));
    }

    @Override public Optional<String> lockUser(long id) { return users.lockExternalIdById(id); }
    @Override public Optional<Long> lockUserByExternalId(String externalId) { return users.lockIdByExternalId(externalId); }
    @Override public Optional<Long> userIdByExternalId(String externalId) { return users.findIdByExternalId(externalId); }
    @Override public Set<Long> lockUsers(Collection<Long> ids) { return new LinkedHashSet<>(users.lockIdsByIdIn(ids)); }

    @Override
    public long createUser(String name, String externalId, String email) {
        return users.saveAndFlush(new User().setName(name).setExternalId(externalId).setEmail(email).setEnabled(true)).getId();
    }

    @Override public void updateUser(long id, String name, String externalId, String email, boolean bumpVersion) { users.updateUser(id, name, externalId, email, bumpVersion ? 1L : 0L); }
    @Override public void updateUserProfile(long id, String name, String email) { users.updateProfile(id, name, email); }
    @Override public void updateUserStatus(long id, boolean enabled) { users.updateStatus(id, enabled); }
    @Override public void invalidateUser(long id) { users.bumpContextVersion(id); }
    @Override public void deleteUser(long id) { users.deleteByIdReturningCount(id); }
    @Override public List<Long> userDepartments(long userId) { return memberships.findDepartmentIdsByUserId(userId); }
    @Override public List<Long> lockedUserDepartments(long userId) { return memberships.findLockedDepartmentIds(userId); }
    @Override public boolean isMember(long userId, long departmentId) { return memberships.existsByUserIdAndDepartmentId(userId, departmentId); }
    @Override public boolean isLockedMember(long userId, long departmentId) { return memberships.existsLocked(userId, departmentId); }
    @Override public long lockedMembersCount(Collection<Long> userIds, long departmentId) { return memberships.countLocked(userIds, departmentId); }
    @Override public void addMembership(long userId, long departmentId) { memberships.addIfAbsent(userId, departmentId); }
    @Override public void removeMembership(long userId, long departmentId) { memberships.deleteByUserIdAndDepartmentId(userId, departmentId); }
    @Override public void clearInactiveDepartment(long userId) { users.clearInactiveDepartment(userId); }

    @Override
    public List<UserMembershipAggregate.DomainGrant> userRoleAssignmentsInDepartments(long userId, Collection<Long> departmentIds) {
        if (departmentIds.isEmpty()) return List.of();
        return userRoles.findAssignmentsInDepartments(userId, departmentIds).stream()
                .map(row -> new UserMembershipAggregate.DomainGrant(
                        row.getDepartmentId(), row.getApplicationId(), row.getRoleId(), row.getRoleCode()))
                .toList();
    }

    @Override
    public void removeUserRolesInDepartments(long userId, Collection<Long> departmentIds) {
        if (!departmentIds.isEmpty()) userRoles.deleteInDepartments(userId, departmentIds);
    }

    @Override
    public List<AccessRoleRecord> accessRoles(long userId) {
        return userRoles.findAccessRoles(userId).stream()
                .map(r -> new AccessRoleRecord(r.getDepartmentId(), r.getDepartmentName(), r.getApplicationId(),
                        r.getApplicationName(), r.isApplicationEnabled(), r.getId(), r.getName(), r.getCode(),
                        r.isEnabled(), r.getExpiresAt(), r.getGrantedBy())).toList();
    }

    @Override
    public List<AccessPermissionRecord> accessPermissions(long userId) {
        var direct = userRoles.findActiveDirectRoles(userId);
        if (direct.isEmpty()) return List.of();
        var grouped = new LinkedHashMap<IsolationDomain, List<Long>>();
        for (var row : direct) {
            grouped.computeIfAbsent(new IsolationDomain(row.getDepartmentId(), row.getApplicationId()), k -> new ArrayList<>())
                    .add(row.getRoleId());
        }
        var result = new ArrayList<AccessPermissionRecord>();
        for (var entry : grouped.entrySet()) {
            long dept = entry.getKey().departmentId(), app = entry.getKey().applicationId();
            var expanded = roleInheritances.expandActiveRoleIds(entry.getValue(), dept, app);
            if (expanded.isEmpty()) continue;
            for (String code : rolePermissions.findActivePermissionCodesByRoleIds(expanded)) {
                result.add(new AccessPermissionRecord(dept, app, code));
            }
        }
        return result;
    }

    @Override
    public List<RoleRecord> roles(long departmentId, long applicationId) {
        var rows = roles.findSummariesByDomain(departmentId, applicationId);
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(RoleRepository.RoleSummary::getId).toList();
        var memberCounts = new HashMap<Long, Long>();
        for (var c : userRoles.countByRoleIds(ids)) memberCounts.put(c.getRoleId(), c.getCount());
        var parentsByChild = new HashMap<Long, List<Long>>();
        for (var edge : roleInheritances.findEdgesByDomain(departmentId, applicationId)) {
            parentsByChild.computeIfAbsent(edge.getChildRoleId(), k -> new ArrayList<>()).add(edge.getParentRoleId());
        }
        return rows.stream().map(r -> new RoleRecord(
                r.getId(), r.getName(), r.getCode(), r.getDescription(), r.isEnabled(), r.getCreatedAt(),
                memberCounts.getOrDefault(r.getId(), 0L),
                List.copyOf(parentsByChild.getOrDefault(r.getId(), List.of())))).toList();
    }

    @Override public long createRole(long departmentId, long applicationId, String name, String code, String description) { return createRole(departmentId, applicationId, name, code, description, true); }

    @Override
    public long createRole(long departmentId, long applicationId, String name, String code, String description, boolean enabled) {
        return roles.saveAndFlush(new Role()
                .setDepartment(departments.getReferenceById(departmentId))
                .setApplication(applications.getReferenceById(applicationId))
                .setName(name).setCode(code).setDescription(description).setEnabled(enabled)).getId();
    }

    @Override public void updateRole(long id, String name, String code, String description) { roles.updateProfile(id, name, code, description); }
    @Override public int updateRoleStatus(long id, boolean enabled) { return roles.updateStatus(id, enabled); }
    @Override public void deleteRole(long id) { roles.deleteByIdReturningCount(id); }

    @Override
    public List<PermissionRecord> permissions(long departmentId, long applicationId) {
        var rows = permissions.findSummariesByDomain(departmentId, applicationId);
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(PermissionRepository.PermissionSummary::getId).toList();
        var roleCounts = new HashMap<Long, Long>();
        for (var c : rolePermissions.countRolesByPermissionIds(ids)) roleCounts.put(c.getPermissionId(), c.getCount());
        return rows.stream().map(p -> new PermissionRecord(
                p.getId(), p.getName(), p.getCode(), p.getResource(), p.getAction(), p.getCreatedAt(),
                roleCounts.getOrDefault(p.getId(), 0L))).toList();
    }

    @Override
    public long createPermission(long departmentId, long applicationId, String name, String code, String resource, String action) {
        return permissions.saveAndFlush(new Permission()
                .setDepartment(departments.getReferenceById(departmentId))
                .setApplication(applications.getReferenceById(applicationId))
                .setName(name).setCode(code).setResource(resource).setAction(action)).getId();
    }

    @Override public void updatePermission(long id, String name, String code, String resource, String action) { permissions.updateProfile(id, name, code, resource, action); }
    @Override public void deletePermission(long id) { permissions.deleteByIdReturningCount(id); }

    @Override
    public List<RolePermissionPair> domainRolePermissionPairs(long departmentId, long applicationId) {
        return rolePermissions.findPairsByDomain(departmentId, applicationId).stream()
                .map(p -> new RolePermissionPair(p.getRoleId(), p.getPermissionId())).toList();
    }

    @Override public List<Long> rolePermissionIds(long roleId) { return rolePermissions.findPermissionIdsByRoleId(roleId); }
    @Override public List<Long> lockedRolePermissionIds(long roleId) { return rolePermissions.findLockedPermissionIds(roleId); }
    @Override public void replaceRolePermissions(long roleId, long departmentId, long applicationId, Collection<Long> permissionIds) { rolePermissions.replaceRolePermissions(roleId, departmentId, applicationId, permissionIds); }
    @Override public List<Long> domainRoleIds(long departmentId, long applicationId) { return roles.findIdsByDomain(departmentId, applicationId); }
    @Override
    public List<RoleInheritanceEdge> domainRoleInheritanceEdges(long departmentId, long applicationId) {
        return roleInheritances.findEdgesByDomain(departmentId, applicationId).stream()
                .map(e -> new RoleInheritanceEdge(e.getChildRoleId(), e.getParentRoleId())).toList();
    }
    @Override
    public void replaceRoleParents(long roleId, long departmentId, long applicationId,
                                   Collection<Long> existingParentIds, Collection<Long> parentIds) {
        roleInheritances.replaceParents(roleId, departmentId, applicationId, existingParentIds, parentIds);
    }
    @Override public boolean lockRole(long roleId) { return roles.lockById(roleId); }
    @Override public Set<Long> lockDomainRolesByIds(long departmentId, long applicationId, Collection<Long> roleIds) { return roles.lockDomainRolesByIds(departmentId, applicationId, roleIds); }

    @Override
    public List<RoleMemberRecord> roleMembers(long roleId) {
        return userRoles.findRoleMembers(roleId).stream()
                .map(m -> new RoleMemberRecord(m.getId(), m.getName(), m.getExternalId(), m.getEmail(), m.isEnabled())).toList();
    }

    @Override public List<Long> roleMemberIds(long roleId) { return userRoles.findRoleMemberIds(roleId); }
    @Override public List<Long> lockedRoleMemberIds(long roleId) { return userRoles.findLockedRoleMemberIds(roleId); }
    @Override public void addRoleMembers(long roleId, long departmentId, long applicationId, Collection<Long> userIds, String actor) { if (!userIds.isEmpty()) userRoles.addRoleMembers(roleId, departmentId, applicationId, userIds, actor); }
    @Override public void removeRoleMembers(long roleId, Collection<Long> userIds) { if (!userIds.isEmpty()) userRoles.deleteRoleMembers(roleId, userIds); }

    @Override
    public Map<Long, Instant> assignmentExpiries(long userId, long departmentId, long applicationId) {
        var result = new LinkedHashMap<Long, Instant>();
        for (var e : userRoles.findExpiriesByDomain(userId, departmentId, applicationId)) {
            result.put(e.getRoleId(), e.getExpiresAt());
        }
        return result;
    }

    @Override public Map<Long, Instant> lockedAssignmentExpiries(long userId, long departmentId, long applicationId) { return userRoles.findLockedAssignments(userId, departmentId, applicationId); }

    @Override
    public PageSlice<RoleMemberRecord> departmentMembers(long departmentId, String keyword, int page, int size) {
        var p = memberships.findDepartmentMembersPage(departmentId, keyword, page, size);
        return new PageSlice<>(p.getContent().stream()
                .map(m -> new RoleMemberRecord(m.id(), m.name(), m.externalId(), m.email(), m.enabled())).toList(), p.getTotalElements());
    }

    @Override
    public List<DomainRolePair> domainRolePairs(long departmentId, long applicationId, Collection<Long> userIds) {
        if (userIds.isEmpty()) return List.of();
        return userRoles.findDomainRolePairs(departmentId, applicationId, userIds).stream()
                .map(p -> new DomainRolePair(p.getUserId(), p.getRoleId(), p.getExpiresAt())).toList();
    }

    @Override public void grantRole(long userId, long departmentId, long applicationId, long roleId, Instant expiresAt, String actor) { userRoles.grantRole(userId, departmentId, applicationId, roleId, expiresAt, actor); }
    @Override public void removeUserRoles(long userId, Collection<Long> roleIds) { if (!roleIds.isEmpty()) userRoles.deleteByUserIdAndRoleIdIn(userId, roleIds); }

    @Override
    public List<DomainAuthorizationAggregate.AssignedDomainRole> assignedRolesInDomain(long userId, long departmentId, long applicationId) {
        return userRoles.findUserRolesInDomain(userId, departmentId, applicationId).stream()
                .map(r -> new DomainAuthorizationAggregate.AssignedDomainRole(
                        r.getId(), r.getName(), r.getCode(), r.isEnabled(), r.getExpiresAt())).toList();
    }

    @Override
    public List<DomainAuthorizationAggregate.PermissionGrant> permissionGrants(long departmentId, long applicationId, Collection<Long> roleIds) {
        if (roleIds.isEmpty()) return List.of();
        var directSources = roleInheritances.mapExpandedToDirectActiveRoles(roleIds, departmentId, applicationId);
        if (directSources.isEmpty()) return List.of();
        var result = new ArrayList<DomainAuthorizationAggregate.PermissionGrant>();
        for (var g : rolePermissions.findGrantsByRoleIds(directSources.keySet())) {
            for (long sourceRoleId : directSources.getOrDefault(g.getRoleId(), List.of(g.getRoleId()))) {
                result.add(new DomainAuthorizationAggregate.PermissionGrant(
                        g.getId(), g.getCode(), g.getName(), g.getResource(), g.getAction(), sourceRoleId));
            }
        }
        return result;
    }

    @Override
    public PageSlice<AuditEventRecord> auditEvents(AuditQuery query, int page, int size) {
        var filter = new AuditEventRepositoryCustom.AuditFilter(query.departmentId(), query.applicationId(),
                toVisibility(query.visibility()), query.actor(), query.action(), query.keyword(), query.from(), query.to());
        var p = auditEvents.findAuditPage(filter, page, size);
        return new PageSlice<>(p.getContent().stream()
                .map(e -> new AuditEventRecord(e.id(), e.actor(), e.action(), e.target(), e.detail(), e.sourceIp(),
                        e.createdAt(), e.departmentId(), e.departmentName(), e.applicationId(), e.applicationName())).toList(), p.getTotalElements());
    }

    @Override
    public DomainOverviewRecord domainOverview(long departmentId, long applicationId) {
        return new DomainOverviewRecord(
                applications.count(),
                applications.countByEnabledTrue(),
                departments.count(),
                users.count(),
                users.countByEnabledTrue(),
                memberships.countByDepartmentId(departmentId),
                roles.countByDepartmentIdAndApplicationId(departmentId, applicationId),
                permissions.countByDepartmentIdAndApplicationId(departmentId, applicationId),
                userRoles.countDistinctUsersInDomain(departmentId, applicationId),
                applicationKeys.countActiveByApplicationId(applicationId),
                contexts.countActiveInDomain(departmentId, applicationId),
                auditEvents.countByCreatedAtAfter(Instant.now().minus(24, ChronoUnit.HOURS)));
    }

    @Override
    public List<DepartmentCodeId> departmentIdsByCodes(Collection<String> codes) {
        if (codes.isEmpty()) return List.of();
        return departments.findCodeIdsByCodeIn(codes).stream()
                .map(d -> new DepartmentCodeId(d.getId(), d.getCode())).toList();
    }

    @Override
    public List<Long> manifestDepartmentIds(long applicationId) {
        var ids = new TreeSet<Long>(permissions.findDistinctDepartmentIdsByApplicationId(applicationId));
        ids.addAll(roles.findDistinctDepartmentIdsByApplicationId(applicationId));
        return new ArrayList<>(ids);
    }

    @Override
    public List<PermissionHolderRecord> permissionHolders(long applicationId, String code, Long departmentId, Collection<Long> visibleDepartments, int limit) {
        return userRoles.findPermissionHolders(applicationId, code, departmentId, visibleDepartments, limit).stream()
                .map(h -> new PermissionHolderRecord(h.userId(), h.userName(), h.externalId(), h.departmentId(),
                        h.departmentName(), h.roleId(), h.roleName(), h.roleCode(), h.expiresAt())).toList();
    }

    @Override
    public long permissionHolderCount(long applicationId, String code, Long departmentId, Collection<Long> visibleDepartments) {
        return userRoles.countPermissionHolders(applicationId, code, departmentId, visibleDepartments);
    }

    @Override
    public List<ExpiringAssignmentRecord> expiringAssignments(Instant before, DomainVisibility visibility, Long applicationId) {
        return userRoles.findExpiringAssignments(before, toVisibility(visibility), applicationId).stream()
                .map(e -> new ExpiringAssignmentRecord(e.userId(), e.userName(), e.externalId(), e.roleId(), e.roleName(),
                        e.roleCode(), e.departmentId(), e.departmentName(), e.applicationId(), e.applicationName(),
                        e.expiresAt(), e.grantedBy(), e.grantedAt())).toList();
    }

    @Override
    public List<LapsedAssignmentRecord> lapsedAssignments(int limit) {
        return userRoles.findLapsedAssignments(Limit.of(limit)).stream()
                .map(l -> new LapsedAssignmentRecord(l.getUserId(), l.getExternalId(), l.getRoleId(),
                        l.getRoleCode(), l.getDepartmentId(), l.getApplicationId())).toList();
    }

    @Override public int deleteLapsedAssignment(long userId, long roleId) { return userRoles.deleteLapsedAssignment(userId, roleId); }

    private static UserRecord toUserRecord(UserRepositoryCustom.UserRow u) {
        return new UserRecord(u.id(), u.name(), u.externalId(), u.email(), u.enabled(),
                u.activeDepartmentId(), u.contextVersion(), u.createdAt());
    }

    static Visibility toVisibility(DomainVisibility v) {
        return v == null ? Visibility.ALL : new Visibility(v.applications(), v.departments());
    }
}
