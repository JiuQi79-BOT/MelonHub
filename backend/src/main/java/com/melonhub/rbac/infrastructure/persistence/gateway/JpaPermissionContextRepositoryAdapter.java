package com.melonhub.rbac.infrastructure.persistence.gateway;

import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ContextRoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.MembershipRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.PermissionContextRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleInheritanceRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RolePermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRoleRepository;
import com.melonhub.rbac.service.ddd.domain.model.PermissionContextAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.PermissionContextRepositoryPort;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

/** DDD 基础设施适配器：将 {@link PermissionContextRepositoryPort} 桥接到 Spring Data JPA 仓储。 */
@Repository
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class JpaPermissionContextRepositoryAdapter implements PermissionContextRepositoryPort {
    private final PermissionContextRepository contexts;
    private final ContextRoleRepository contextRoles;
    private final UserRepository users;
    private final ApplicationRepository applications;
    private final DepartmentRepository departments;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final RoleInheritanceRepository roleInheritances;
    private final UserRoleRepository userRoles;

    public JpaPermissionContextRepositoryAdapter(PermissionContextRepository contexts, ContextRoleRepository contextRoles,
                                                 UserRepository users, ApplicationRepository applications,
                                                 DepartmentRepository departments, MembershipRepository memberships,
                                                 RoleRepository roles, RolePermissionRepository rolePermissions,
                                                 RoleInheritanceRepository roleInheritances, UserRoleRepository userRoles) {
        this.contexts = contexts;
        this.contextRoles = contextRoles;
        this.users = users;
        this.applications = applications;
        this.departments = departments;
        this.memberships = memberships;
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.roleInheritances = roleInheritances;
        this.userRoles = userRoles;
    }

    @Override
    public Optional<LockedUserContextRow> lockUserByExternalId(String externalId) {
        return users.lockRowByExternalId(externalId).map(row -> new LockedUserContextRow(
                row.id(), row.enabled(), row.activeDepartmentId(), row.contextVersion()));
    }

    @Override public boolean isApplicationEnabled(long applicationId) { return applications.existsByIdAndEnabledTrue(applicationId); }
    @Override public boolean isMember(long userId, long departmentId) { return memberships.existsByUserIdAndDepartmentId(userId, departmentId); }
    @Override public List<Long> assignedRoleIds(long userId, long departmentId, long applicationId) { return userRoles.findActiveAssignedRoleIds(userId, departmentId, applicationId); }

    @Override
    public void switchDepartment(long userId, long departmentId, long newVersion) {
        users.switchDepartment(userId, departments.getReferenceById(departmentId), newVersion);
    }

    @Override public void clearStaleContexts(long userId, long version) { contexts.deleteStaleForUser(userId, version); }

    @Override
    public void createContext(String tokenHash, long userId, long departmentId, long applicationId, long version, Instant expiresAt) {
        contexts.persistContext(tokenHash, users.getReferenceById(userId), departmentId, applicationId, version, expiresAt);
    }

    @Override
    public void activateRoles(String tokenHash, Collection<Long> roleIds) {
        contextRoles.activateRoles(tokenHash, contexts.getReferenceById(tokenHash), roleIds, roles::getReferenceById);
    }

    @Override
    public Optional<StoredContextState> contextState(String tokenHash) {
        return contexts.findStateById(tokenHash)
                .filter(s -> s.getDepartmentId() != null)
                .map(s -> new StoredContextState(
                        s.getUserId(), s.getDepartmentId(), s.getApplicationId(),
                        s.getContextVersion(), s.getExpiresAt(), s.isValid()));
    }

    @Override
    public EffectiveAccess effectiveAccess(long userId, String tokenHash, long departmentId, long applicationId) {
        var activated = contextRoles.findRoleIdsByContextId(tokenHash);
        if (activated.isEmpty()) return new EffectiveAccess(List.of(), List.of());
        var directActive = roles.findEffectiveRoles(userId, activated, departmentId, applicationId);
        if (directActive.isEmpty()) return new EffectiveAccess(List.of(), List.of());
        var effectiveRoles = directActive.stream()
                .map(r -> new PermissionContextAggregate.EffectiveRole(r.getId(), r.getName(), r.getCode(), r.getExpiresAt()))
                .toList();
        var activeRootIds = directActive.stream().map(RoleRepository.EffectiveRole::getId).toList();
        var expanded = roleInheritances.expandActiveRoleIds(activeRootIds, departmentId, applicationId);
        var effectivePermissions = expanded.isEmpty() ? List.<String>of()
                : rolePermissions.findActivePermissionCodesInDomain(expanded, departmentId, applicationId);
        return new EffectiveAccess(effectiveRoles, effectivePermissions);
    }

    @Override public void revokeByHash(String tokenHash, long applicationId) { contexts.revokeByIdAndApplicationId(tokenHash, applicationId); }

    @Override
    public List<ActiveContextRecord> activeContexts(long userId) {
        var active = contexts.findActiveByUserId(userId, Limit.of(200));
        if (active.isEmpty()) return List.of();
        var ids = active.stream().map(PermissionContextRepository.ActiveContext::getId).toList();
        var counts = new HashMap<String, Long>();
        for (var c : contextRoles.countByContextIds(ids)) counts.put(c.getContextId(), c.getCount());
        return active.stream().map(c -> new ActiveContextRecord(
                c.getDepartmentId(), c.getDepartmentName(), c.getApplicationId(), c.getApplicationName(),
                c.getCreatedAt(), c.getExpiresAt(), counts.getOrDefault(c.getId(), 0L), c.isValid() ? 1L : 0L)).toList();
    }

    @Override public int revokeUser(long userId) { return contexts.revokeByUserId(userId); }
    @Override public void revokeDomain(long userId, long departmentId, long applicationId) { contexts.revokeDomain(userId, departmentId, applicationId); }

    @Override
    public void revokeDomainForUsers(Collection<Long> userIds, long departmentId, long applicationId) {
        if (!userIds.isEmpty()) contexts.revokeDomainForUsers(userIds, departmentId, applicationId);
    }

    @Override public void revokeApplication(long applicationId) { contexts.clearByApplicationId(applicationId); }
    @Override public void revokeDepartment(long departmentId) { contexts.clearByDepartmentId(departmentId); }
    @Override public int purgeExpired(int limit) { return contexts.purgeExpired(limit); }
}
