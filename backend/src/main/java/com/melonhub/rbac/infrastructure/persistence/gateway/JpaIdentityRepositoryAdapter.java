package com.melonhub.rbac.infrastructure.persistence.gateway;

import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.IdentityCredentialRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.IdentitySessionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.MembershipRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleInheritanceRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RolePermissionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.RoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.UserRoleRepository;
import com.melonhub.rbac.service.ddd.domain.model.IdentityAccountAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.IdentityRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.PageSlice;
import com.melonhub.rbac.service.ddd.domain.value.IsolationDomain;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** DDD 基础设施适配器：将 {@link IdentityRepositoryPort} 桥接到 Spring Data JPA 仓储。 */
@Repository
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class JpaIdentityRepositoryAdapter implements IdentityRepositoryPort {
    private final IdentityCredentialRepository credentials;
    private final IdentitySessionRepository sessions;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final RoleInheritanceRepository roleInheritances;
    private final UserRoleRepository userRoles;
    private final AuditEventRepository auditEvents;

    public JpaIdentityRepositoryAdapter(IdentityCredentialRepository credentials, IdentitySessionRepository sessions,
                                        UserRepository users, DepartmentRepository departments,
                                        MembershipRepository memberships, RoleRepository roles,
                                        RolePermissionRepository rolePermissions, RoleInheritanceRepository roleInheritances,
                                        UserRoleRepository userRoles, AuditEventRepository auditEvents) {
        this.credentials = credentials;
        this.sessions = sessions;
        this.users = users;
        this.departments = departments;
        this.memberships = memberships;
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.roleInheritances = roleInheritances;
        this.userRoles = userRoles;
        this.auditEvents = auditEvents;
    }

    @Override
    public Optional<IdentityAccountAggregate.IdentityCredentialSnapshot> findCredential(String username) {
        return credentials.findActiveByUsername(username).map(c -> new IdentityAccountAggregate.IdentityCredentialSnapshot(
                c.getUserId(), c.getUsername(), c.getPasswordHash()));
    }

    @Override
    public Optional<IdentityAccountAggregate.IdentityUser> findUserByExternalId(String externalId) {
        return users.findActiveIdentityUserByExternalId(externalId)
                .map(u -> new IdentityAccountAggregate.IdentityUser(u.getId(), u.getExternalId(), u.getName()));
    }

    @Override
    public Optional<Long> findAnyUserIdByExternalId(String externalId) {
        return users.findIdByExternalId(externalId);
    }

    @Override
    public boolean createSession(String tokenHash, IdentityAccountAggregate.IdentityCredentialSnapshot credential,
                                 String systemCode, Instant expiresAt) {
        return credentials.createSessionIfVerified(tokenHash, credential.userId(), credential.username(),
                credential.passwordHash(), systemCode, expiresAt);
    }

    @Override public boolean hasActiveSession(String tokenHash, Instant now) { return sessions.hasActiveSession(tokenHash, now); }
    @Override public void revokeSession(String tokenHash, Instant now) { sessions.revokeByTokenHash(tokenHash, now); }
    @Override public void upsertCredential(long userId, String username, String passwordHash) { credentials.upsertCredential(userId, username, passwordHash); }
    @Override public void renameCredential(long userId, String newUsername) { credentials.renameUsername(userId, newUsername); }
    @Override public void revokeUserSessions(long userId) { sessions.revokeUserSessions(userId); }

    @Override
    public List<String> roles(long userId, String systemCode) {
        var effective = effectiveRoleIds(userId, systemCode);
        return effective.isEmpty() ? List.of() : roles.findActiveCodesByIdIn(effective);
    }

    @Override
    public List<String> permissions(long userId, String systemCode) {
        var effective = effectiveRoleIds(userId, systemCode);
        return effective.isEmpty() ? List.of() : rolePermissions.findActivePermissionCodesByRoleIds(effective);
    }

    @Override
    public List<Long> permissionDepartments(long userId, String systemCode, String permissionCode) {
        var effective = effectiveRoleIds(userId, systemCode);
        return effective.isEmpty() ? List.of() : rolePermissions.findDepartmentIdsWithPermission(effective, permissionCode);
    }

    @Override
    public List<DepartmentRecord> departments(String keyword, Long parentId, int page, int pageSize) {
        return departments.findIdentityDepartments(keyword, parentId, page, pageSize).stream()
                .map(d -> new DepartmentRecord(d.id(), d.code(), d.name(), d.parentId(), d.hasChildren()))
                .toList();
    }

    @Override public long departmentCount(String keyword, Long parentId) { return departments.countIdentityDepartments(keyword, parentId); }
    @Override public boolean departmentExists(long departmentId) { return departments.existsById(departmentId); }

    @Override
    public List<DepartmentUserRecord> departmentUsers(long departmentId, String keyword, int page, int pageSize) {
        return memberships.findIdentityDepartmentUsers(departmentId, keyword, page, pageSize).stream()
                .map(u -> new DepartmentUserRecord(u.userId(), u.no(), u.name(), u.departmentId(), u.departmentName()))
                .toList();
    }

    @Override public long departmentUserCount(long departmentId, String keyword) { return memberships.countIdentityDepartmentUsers(departmentId, keyword); }

    @Override
    public PageSlice<IdentityAccountAggregate.CourseAuditRecord> courseAuditEvents(
            String systemCode, Collection<Long> departmentIds, String actor, Long actorId,
            String target, Instant from, Instant to, int page, int pageSize) {
        var result = auditEvents.findCourseAuditPage(systemCode, List.copyOf(departmentIds), actor, actorId,
                target, from, to, page, pageSize);
        var items = result.getContent().stream().map(row -> new IdentityAccountAggregate.CourseAuditRecord(
                row.id(), row.at(), row.type(), row.target(), row.actorNo(), row.actorId(), row.actorName())).toList();
        return new PageSlice<>(items, result.getTotalElements());
    }

    @Override
    public Map<Long, List<String>> auditActorRoles(Collection<Long> actorIds, String systemCode, Collection<Long> departmentIds) {
        if (actorIds.isEmpty() || departmentIds.isEmpty()) return Map.of();
        Map<Long, List<String>> result = new HashMap<>();
        for (var row : userRoles.findAuditActorRoles(actorIds, systemCode, departmentIds)) {
            result.computeIfAbsent(row.getUserId(), ignored -> new ArrayList<>())
                    .add(row.getCode().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private List<Long> effectiveRoleIds(long userId, String systemCode) {
        var direct = userRoles.findActiveIdentityRoles(userId, systemCode);
        if (direct.isEmpty()) return List.of();
        var grouped = new LinkedHashMap<IsolationDomain, List<Long>>();
        for (var row : direct) {
            grouped.computeIfAbsent(new IsolationDomain(row.getDepartmentId(), row.getApplicationId()), k -> new ArrayList<>())
                    .add(row.getRoleId());
        }
        var result = new LinkedHashSet<Long>();
        for (var entry : grouped.entrySet()) {
            result.addAll(roleInheritances.expandActiveRoleIds(
                    entry.getValue(), entry.getKey().departmentId(), entry.getKey().applicationId()));
        }
        return new ArrayList<>(result);
    }
}
