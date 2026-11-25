package com.melonhub.rbac.service.ddd.infrastructure;

import com.melonhub.rbac.dao.RbacDao;
import com.melonhub.rbac.dao.Visibility;
import com.melonhub.rbac.service.ddd.domain.model.UserMembershipAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.PageSlice;
import com.melonhub.rbac.service.ddd.domain.repository.RbacRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.value.DomainVisibility;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** DDD 基础设施适配器：将 {@link RbacRepositoryPort} 桥接到共享的 JPA {@link RbacDao}。 */
@Component
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class JpaRbacRepositoryAdapter implements RbacRepositoryPort {
    private final RbacDao dao;

    public JpaRbacRepositoryAdapter(RbacDao dao) {
        this.dao = dao;
    }

    @Override public boolean healthy() { return dao.healthy(); }
    @Override public void recordAudit(String actor, String action, String target, Long departmentId, Long applicationId, String detail, String sourceIp) {
        dao.audit(actor, action, target, departmentId, applicationId, detail, sourceIp);
    }
    @Override public boolean departmentExists(long id) { return dao.departmentExists(id); }
    @Override public boolean applicationExists(long id) { return dao.applicationExists(id); }
    @Override public Optional<String[]> entityLabel(String table, long id) { return dao.label(table, id); }
    @Override public boolean scopedExists(String table, long id, long departmentId, long applicationId) { return dao.scopedExists(table, id, departmentId, applicationId); }
    @Override public long scopedCount(String table, Collection<Long> ids, long departmentId, long applicationId) { return dao.scopedCount(table, ids, departmentId, applicationId); }
    @Override public Optional<Long> scopedIdByCode(String table, long departmentId, long applicationId, String code) { return dao.scopedIdByCode(table, departmentId, applicationId, code); }
    @Override public List<String> codes(String table, Collection<Long> ids) { return dao.codes(table, ids); }
    @Override public List<Map<String, Object>> departments(Collection<Long> visibleDepartments) { return dao.departments(visibleDepartments); }
    @Override public long createDepartment(String name, String code) { return dao.createDepartment(name, code); }
    @Override public int updateDepartment(long id, String name, String code) { return dao.updateDepartment(id, name, code); }
    @Override public long[] departmentUsage(long id) { return dao.departmentUsage(id); }
    @Override public int deleteDepartment(long id) { return dao.deleteDepartment(id); }
    @Override public List<Map<String, Object>> applications(Collection<Long> visibleApplications) { return dao.applications(visibleApplications); }
    @Override public Optional<Map<String, Object>> applicationRow(long id) { return dao.applicationRow(id); }
    @Override public boolean applicationEnabled(long id) { return dao.applicationEnabled(id); }
    @Override public long createApplication(String name, String code, String description, String owner) { return dao.createApplication(name, code, description, owner); }
    @Override public int updateApplication(long id, String name, String code, String description, String owner) { return dao.updateApplication(id, name, code, description, owner); }
    @Override public int updateApplicationStatus(long id, boolean enabled) { return dao.applicationStatus(id, enabled); }
    @Override public long[] applicationUsage(long id) { return dao.applicationUsage(id); }
    @Override public int deleteApplication(long id) { return dao.deleteApplication(id); }
    @Override public boolean lockApplication(long id) { return dao.lockApplication(id); }
    @Override public int lockedActiveKeyCount(long applicationId) { return (int) dao.lockedActiveKeyCount(applicationId); }
    @Override public List<Map<String, Object>> applicationKeys(long applicationId) { return dao.keys(applicationId); }
    @Override public Optional<String> applicationKeyName(long applicationId, long keyId) { return dao.keyName(applicationId, keyId); }
    @Override public long createApplicationKey(long applicationId, String name, String prefix, String hash, String actor, Instant expiresAt) {
        return dao.createKey(applicationId, name, prefix, hash, actor, ts(expiresAt));
    }
    @Override public int revokeApplicationKey(long applicationId, long keyId) { return dao.revokeKey(applicationId, keyId); }
    @Override public int revokeAllApplicationKeys(long applicationId) { return dao.revokeAllKeys(applicationId); }
    @Override public int updateApplicationRateLimit(long id, Integer perSecond) { return dao.setRateLimit(id, perSecond); }
    @Override public PageSlice users(String keyword, Long departmentId, Boolean enabled, Collection<Long> visibleDepartments, int page, int size) {
        var p = dao.users(keyword, departmentId, enabled, visibleDepartments, page, size);
        return new PageSlice(p.items(), p.total());
    }
    @Override public List<Map<String, Object>> memberships(Collection<Long> userIds) { return dao.memberships(userIds); }
    @Override public List<Map<String, Object>> departmentUsers(long departmentId) { return dao.departmentUsers(departmentId); }
    @Override public Optional<Map<String, Object>> user(long id) { return dao.user(id); }
    @Override public Optional<String> lockUser(long id) { return dao.lockUser(id); }
    @Override public Optional<Long> lockUserByExternalId(String externalId) { return dao.lockUserByExternal(externalId); }
    @Override public Optional<Long> userIdByExternalId(String externalId) { return dao.userIdByExternal(externalId); }
    @Override public Set<Long> lockUsers(Collection<Long> ids) { return new LinkedHashSet<>(dao.lockUsers(ids)); }
    @Override public long createUser(String name, String externalId, String email) { return dao.createUser(name, externalId, email); }
    @Override public void updateUser(long id, String name, String externalId, String email, boolean bumpVersion) { dao.updateUser(id, name, externalId, email, bumpVersion); }
    @Override public void updateUserProfile(long id, String name, String email) { dao.updateProfile(id, name, email); }
    @Override public void updateUserStatus(long id, boolean enabled) { dao.userStatus(id, enabled); }
    @Override public void invalidateUser(long id) { dao.invalidateUser(id); }
    @Override public void deleteUser(long id) { dao.deleteUser(id); }
    @Override public List<Long> userDepartments(long userId) { return dao.userDepartments(userId); }
    @Override public List<Long> lockedUserDepartments(long userId) { return dao.lockedUserDepartments(userId); }
    @Override public boolean isMember(long userId, long departmentId) { return dao.member(userId, departmentId); }
    @Override public boolean isLockedMember(long userId, long departmentId) { return dao.lockedMember(userId, departmentId); }
    @Override public long lockedMembersCount(Collection<Long> userIds, long departmentId) { return dao.lockedMembers(userIds, departmentId); }
    @Override public void addMembership(long userId, long departmentId) { dao.addMembership(userId, departmentId); }
    @Override public void removeMembership(long userId, long departmentId) { dao.removeMembership(userId, departmentId); }
    @Override public void clearInactiveDepartment(long userId) { dao.clearInactiveDepartment(userId); }
    @Override public List<UserMembershipAggregate.DomainGrant> userRoleAssignmentsInDepartments(long userId, Collection<Long> departmentIds) {
        return dao.userRoleAssignments(userId, departmentIds).stream()
                .map(row -> new UserMembershipAggregate.DomainGrant(
                        ((Number) row.get("department_id")).longValue(),
                        ((Number) row.get("application_id")).longValue(),
                        ((Number) row.get("role_id")).longValue(),
                        String.valueOf(row.get("role_code"))))
                .toList();
    }
    @Override public void removeUserRolesInDepartments(long userId, Collection<Long> departmentIds) { dao.removeUserRolesInDepartments(userId, departmentIds); }
    @Override public List<Map<String, Object>> accessRoles(long userId) { return dao.accessRoles(userId); }
    @Override public List<Map<String, Object>> accessPermissions(long userId) { return dao.accessPermissions(userId); }
    @Override public List<Map<String, Object>> roles(long departmentId, long applicationId) { return dao.roles(departmentId, applicationId); }
    @Override public long createRole(long departmentId, long applicationId, String name, String code, String description) { return dao.createRole(departmentId, applicationId, name, code, description); }
    @Override public long createRole(long departmentId, long applicationId, String name, String code, String description, boolean enabled) { return dao.createRole(departmentId, applicationId, name, code, description, enabled); }
    @Override public int updateRole(long id, String name, String code, String description) { dao.updateRole(id, name, code, description); return 1; }
    @Override public int updateRoleStatus(long id, boolean enabled) { return dao.roleStatus(id, enabled); }
    @Override public void deleteRole(long id) { dao.deleteRole(id); }
    @Override public List<Map<String, Object>> permissions(long departmentId, long applicationId) { return dao.permissions(departmentId, applicationId); }
    @Override public long createPermission(long departmentId, long applicationId, String name, String code, String resource, String action) { return dao.createPermission(departmentId, applicationId, name, code, resource, action); }
    @Override public int updatePermission(long id, String name, String code, String resource, String action) { dao.updatePermission(id, name, code, resource, action); return 1; }
    @Override public void deletePermission(long id) { dao.deletePermission(id); }
    @Override public List<Map<String, Object>> domainRolePermissionPairs(long departmentId, long applicationId) { return dao.rolePermissions(departmentId, applicationId); }
    @Override public List<Long> rolePermissionIds(long roleId) { return dao.rolePermissionIds(roleId); }
    @Override public List<Long> lockedRolePermissionIds(long roleId) { return dao.lockedRolePermissionIds(roleId); }
    @Override public void replaceRolePermissions(long roleId, long departmentId, long applicationId, Collection<Long> permissionIds) { dao.replaceRolePermissions(roleId, departmentId, applicationId, permissionIds); }
    @Override public List<Map<String, Object>> domainRoleInheritancePairs(long departmentId, long applicationId) { return dao.roleInheritancePairs(departmentId, applicationId); }
    @Override public List<Long> roleParentIds(long roleId, long departmentId, long applicationId) { return dao.roleParentIds(roleId, departmentId, applicationId); }
    @Override public List<Long> inheritedRoleIds(Collection<Long> roleIds, long departmentId, long applicationId) { return dao.inheritedRoleIds(roleIds, departmentId, applicationId); }
    @Override public void replaceRoleParents(long roleId, long departmentId, long applicationId, Collection<Long> parentIds) { dao.replaceRoleParents(roleId, departmentId, applicationId, parentIds); }
    @Override public boolean lockRole(long roleId) { return dao.lockRole(roleId); }
    @Override public List<Map<String, Object>> roleMembers(long roleId) { return dao.roleMembers(roleId); }
    @Override public List<Long> roleMemberIds(long roleId) { return dao.roleMemberIds(roleId); }
    @Override public List<Long> lockedRoleMemberIds(long roleId) { return dao.lockedRoleMemberIds(roleId); }
    @Override public void addRoleMembers(long roleId, long departmentId, long applicationId, Collection<Long> userIds, String actor) { dao.addRoleMembers(roleId, departmentId, applicationId, userIds, actor); }
    @Override public void removeRoleMembers(long roleId, Collection<Long> userIds) { dao.removeRoleMembers(roleId, userIds); }
    @Override public Map<Long, Object> assignmentExpiryViews(long userId, long departmentId, long applicationId) {
        var result = new LinkedHashMap<Long, Object>();
        result.putAll(dao.assignmentExpiries(userId, departmentId, applicationId));
        return result;
    }
    @Override public Map<Long, Instant> lockedAssignmentExpiries(long userId, long departmentId, long applicationId) {
        var result = new LinkedHashMap<Long, Instant>();
        dao.lockedAssignments(userId, departmentId, applicationId).forEach((roleId, ts) -> result.put(roleId, ts == null ? null : ts.toInstant()));
        return result;
    }
    @Override public PageSlice departmentMembers(long departmentId, String keyword, int page, int size) {
        var p = dao.departmentMembers(departmentId, keyword, page, size);
        return new PageSlice(p.items(), p.total());
    }
    @Override public List<Map<String, Object>> domainRolePairs(long departmentId, long applicationId, Collection<Long> userIds) { return dao.domainRolePairs(departmentId, applicationId, userIds); }
    @Override public void grantRole(long userId, long departmentId, long applicationId, long roleId, Instant expiresAt, String actor) {
        dao.grantRole(userId, departmentId, applicationId, roleId, ts(expiresAt), actor);
    }
    @Override public void removeUserRoles(long userId, Collection<Long> roleIds) { dao.removeUserRoles(userId, roleIds); }
    @Override public List<Map<String, Object>> userRolesInDomain(long userId, long departmentId, long applicationId) { return dao.userRolesInDomain(userId, departmentId, applicationId); }
    @Override
    public List<com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate.AssignedDomainRole> assignedRolesInDomain(
            long userId, long departmentId, long applicationId) {
        return dao.userRolesInDomain(userId, departmentId, applicationId).stream()
                .map(row -> new com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate.AssignedDomainRole(
                        ((Number) row.get("id")).longValue(),
                        (String) row.get("name"),
                        (String) row.get("code"),
                        Boolean.TRUE.equals(row.get("enabled")),
                        row.get("expiresAt") instanceof Timestamp t ? t.toInstant() : null,
                        row.get("expiresAt")))
                .toList();
    }
    @Override
    public List<com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate.PermissionGrant> permissionGrantsTyped(
            Collection<Long> roleIds) {
        return dao.grants(roleIds).stream()
                .map(row -> new com.melonhub.rbac.service.ddd.domain.model.DomainAuthorizationAggregate.PermissionGrant(
                        ((Number) row.get("id")).longValue(),
                        (String) row.get("code"),
                        (String) row.get("name"),
                        (String) row.get("resource"),
                        (String) row.get("action"),
                        ((Number) row.get("role_id")).longValue()))
                .toList();
    }
    @Override public List<Map<String, Object>> permissionGrantsForRoles(Collection<Long> roleIds) { return dao.grants(roleIds); }
    @Override public PageSlice auditEvents(AuditQuery query, int page, int size) {
        var filter = new RbacDao.AuditFilter(query.departmentId(), query.applicationId(), toVisibility(query.visibility()),
                query.actor(), query.action(), query.keyword(), ts(query.from()), ts(query.to()));
        var p = dao.events(filter, page, size);
        return new PageSlice(p.items(), p.total());
    }
    @Override public Map<String, Object> domainOverview(long departmentId, long applicationId) { return dao.overview(departmentId, applicationId); }
    @Override public List<Map<String, Object>> departmentIdsByCodes(Collection<String> codes) { return dao.departmentIdsByCodes(codes); }
    @Override public List<Long> manifestDepartmentIds(long applicationId) { return dao.manifestDepartmentIds(applicationId); }
    @Override public List<Map<String, Object>> permissionHolders(long applicationId, String code, Long departmentId, Collection<Long> visibleDepartments, int limit) {
        return dao.permissionHolders(applicationId, code, departmentId, visibleDepartments, limit);
    }
    @Override public long permissionHolderCount(long applicationId, String code, Long departmentId, Collection<Long> visibleDepartments) {
        return dao.permissionHolderCount(applicationId, code, departmentId, visibleDepartments);
    }
    @Override public List<Map<String, Object>> expiringAssignments(Instant before, DomainVisibility visibility, Long applicationId) {
        return dao.expiringAssignments(ts(before), toVisibility(visibility), applicationId);
    }
    @Override public List<Map<String, Object>> lapsedAssignments(int limit) { return dao.lapsedAssignments(limit); }
    @Override public int deleteLapsedAssignment(long userId, long roleId) { return dao.deleteLapsedAssignment(userId, roleId); }

    static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    static Visibility toVisibility(DomainVisibility v) { return v == null ? Visibility.ALL : new Visibility(v.applications(), v.departments()); }
}
