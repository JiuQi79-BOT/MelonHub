package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import com.melonhub.rbac.infrastructure.persistence.entity.UserRole;
import com.melonhub.rbac.infrastructure.persistence.entity.UserRoleId;
import com.melonhub.rbac.service.port.security.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class UserRoleRepositoryImpl implements UserRoleRepositoryCustom {
    private static final String CURRENT = "(ur.expiresAt is null or ur.expiresAt > current_timestamp)";
    private static final String HOLDERS = """
            from UserRole ur join ur.user u join ur.role r join ur.department d
            where ur.role.id in :roleIds and u.enabled = true and r.enabled = true
              and ur.applicationId = :app and\s""" + CURRENT;

    private final EntityManager em;

    UserRoleRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    private UserRole newAssignment(long userId, long roleId, long deptId, long appId,
                                   Instant expiresAt, String grantedBy, Instant now) {
        return new UserRole()
                .setId(new UserRoleId(userId, roleId))
                .setUser(em.getReference(User.class, userId))
                .setRole(em.getReference(Role.class, roleId))
                .setDepartmentId(deptId)
                .setApplicationId(appId)
                .setExpiresAt(expiresAt)
                .setGrantedBy(JpaQuery.truncate(grantedBy, 100))
                .setGrantedAt(now)
                .setGrantId(UUID.randomUUID().toString());
    }

    @Override
    public Map<Long, Instant> findLockedAssignments(long userId, long departmentId, long applicationId) {
        var query = em.createQuery("""
                        select ur from UserRole ur
                        where ur.user.id = :user and ur.departmentId = :dept and ur.applicationId = :app
                        order by ur.role.id""", UserRole.class)
                .setParameter("user", userId).setParameter("dept", departmentId).setParameter("app", applicationId);
        var result = new LinkedHashMap<Long, Instant>();
        for (UserRole ur : JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_WRITE)) {
            result.put(ur.getId().getRoleId(), ur.getExpiresAt());
        }
        return result;
    }

    @Override
    public List<Long> findLockedRoleMemberIds(long roleId) {
        var query = em.createQuery("select ur from UserRole ur where ur.role.id = :role order by ur.user.id", UserRole.class)
                .setParameter("role", roleId);
        return JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_WRITE).stream()
                .map(ur -> ur.getId().getUserId()).toList();
    }

    @Override
    public void addRoleMembers(long roleId, long departmentId, long applicationId, Collection<Long> userIds, String grantedBy) {
        Instant now = JpaQuery.now();
        for (long userId : userIds) {
            em.persist(newAssignment(userId, roleId, departmentId, applicationId, null, grantedBy, now));
        }
        em.flush();
    }

    @Override
    public void grantRole(long userId, long departmentId, long applicationId, long roleId, Instant expiresAt, String grantedBy) {
        Instant now = JpaQuery.now();
        UserRole existing = em.find(UserRole.class, new UserRoleId(userId, roleId), LockModeType.PESSIMISTIC_WRITE);
        if (existing == null) {
            em.persist(newAssignment(userId, roleId, departmentId, applicationId, expiresAt, grantedBy, now));
        } else {
            existing.setExpiresAt(expiresAt)
                    .setGrantedBy(JpaQuery.truncate(grantedBy, 100))
                    .setGrantedAt(now)
                    .setGrantId(UUID.randomUUID().toString());
        }
        em.flush();
    }

    /**
     * 先定位直接绑定目标权限码的启用角色，再沿同隔离域启用继承边反向展开所有继承该权限的子孙角色。
     */
    private Set<Long> effectiveHolderRoleIds(long app, String code, Long dept, Collection<Long> visibleDepartments) {
        if (visibleDepartments != null && visibleDepartments.isEmpty()) {
            return Set.of();
        }
        var sql = new StringBuilder("""
                select distinct r.id, r.department.id
                from RolePermission rp join rp.role r join rp.permission p
                where r.enabled = true and p.application.id = :app and p.code = :code
                  and r.department.id = p.department.id""");
        if (dept != null) sql.append(" and r.department.id = :dept");
        if (visibleDepartments != null) sql.append(" and r.department.id in :visible");
        var directQuery = em.createQuery(sql.toString(), Tuple.class)
                .setParameter("app", app)
                .setParameter("code", code);
        if (dept != null) directQuery.setParameter("dept", dept);
        if (visibleDepartments != null) directQuery.setParameter("visible", visibleDepartments);
        List<Tuple> directRows = directQuery.getResultList();
        if (directRows.isEmpty()) {
            return Set.of();
        }
        Set<Long> directRoles = new LinkedHashSet<>();
        Set<Long> depts = new LinkedHashSet<>();
        for (Tuple row : directRows) {
            directRoles.add(row.get(0, Long.class));
            depts.add(row.get(1, Long.class));
        }
        List<Tuple> edgeRows = em.createQuery("""
                        select ri.id.childRoleId, ri.id.parentRoleId
                        from RoleInheritance ri join ri.childRole c join ri.parentRole p
                        where ri.applicationId = :app and ri.departmentId in :depts
                          and c.enabled = true and p.enabled = true""", Tuple.class)
                .setParameter("app", app)
                .setParameter("depts", depts)
                .getResultList();
        Map<Long, List<Long>> childrenByParent = new LinkedHashMap<>();
        for (Tuple edge : edgeRows) {
            childrenByParent.computeIfAbsent(edge.get(1, Long.class), k -> new ArrayList<>())
                    .add(edge.get(0, Long.class));
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

    private TypedQuery<Tuple> holdersQuery(String select, Long dept, Collection<Long> visibleDepartments,
                                           long app, Collection<Long> roleIds, String order) {
        var text = new StringBuilder(select).append(' ').append(HOLDERS);
        if (dept != null) text.append(" and ur.departmentId = :dept");
        if (visibleDepartments != null) {
            text.append(visibleDepartments.isEmpty() ? " and 1=0" : " and ur.departmentId in :visible");
        }
        var query = em.createQuery(text + order, Tuple.class)
                .setParameter("app", app)
                .setParameter("roleIds", roleIds);
        if (dept != null) query.setParameter("dept", dept);
        if (visibleDepartments != null && !visibleDepartments.isEmpty()) query.setParameter("visible", visibleDepartments);
        return query;
    }

    @Override
    public long countPermissionHolders(long applicationId, String permissionCode, Long departmentId,
                                       Collection<Long> visibleDepartments) {
        Set<Long> roleIds = effectiveHolderRoleIds(applicationId, permissionCode, departmentId, visibleDepartments);
        if (roleIds.isEmpty()) return 0L;
        return holdersQuery("select distinct ur.departmentId, u.id", departmentId, visibleDepartments,
                applicationId, roleIds, "").getResultList().size();
    }

    @Override
    public List<PermissionHolderRow> findPermissionHolders(long applicationId, String permissionCode, Long departmentId,
                                                           Collection<Long> visibleDepartments, int limit) {
        Set<Long> roleIds = effectiveHolderRoleIds(applicationId, permissionCode, departmentId, visibleDepartments);
        if (roleIds.isEmpty()) return List.of();
        var first = holdersQuery("select distinct ur.departmentId, u.id", departmentId, visibleDepartments,
                applicationId, roleIds, " order by ur.departmentId, u.id")
                .setMaxResults(limit).getResultList();
        if (first.isEmpty()) return List.of();
        var pairs = new LinkedHashSet<String>();
        var users = new LinkedHashSet<Long>();
        for (var t : first) {
            pairs.add(t.get(0) + ":" + t.get(1));
            users.add(t.get(1, Long.class));
        }
        var query = holdersQuery("""
                select u.id as userId, u.name as userName, u.externalId as externalId,
                       ur.departmentId as departmentId, d.name as departmentName,
                       r.id as roleId, r.name as roleName, r.code as roleCode, ur.expiresAt as expiresAt""",
                departmentId, visibleDepartments, applicationId, roleIds,
                " and u.id in :users order by ur.departmentId, u.id, r.id");
        return query.setParameter("users", users).getResultList().stream()
                .map(t -> new PermissionHolderRow(
                        t.get("userId", Long.class), t.get("userName", String.class), t.get("externalId", String.class),
                        t.get("departmentId", Long.class), t.get("departmentName", String.class),
                        t.get("roleId", Long.class), t.get("roleName", String.class), t.get("roleCode", String.class),
                        t.get("expiresAt", Instant.class)))
                .filter(row -> pairs.contains(row.departmentId() + ":" + row.userId()))
                .toList();
    }

    @Override
    public List<ExpiringAssignmentRow> findExpiringAssignments(Instant deadline, Visibility visible, Long applicationId) {
        var where = new JpaQuery.Where().and("ur.expiresAt is not null and ur.expiresAt <= ?", deadline);
        if (visible.applications() != null) where.in("ur.applicationId", visible.applications());
        if (visible.departments() != null) where.in("ur.departmentId", visible.departments());
        if (applicationId != null) where.and("ur.applicationId = ?", applicationId);
        return where.bind(em.createQuery("""
                        select u.id as userId, u.name as userName, u.externalId as externalId,
                               r.id as roleId, r.name as roleName, r.code as roleCode,
                               ur.departmentId as departmentId, d.name as departmentName,
                               ur.applicationId as applicationId, a.name as applicationName,
                               ur.expiresAt as expiresAt, ur.grantedBy as grantedBy, ur.grantedAt as grantedAt
                        from UserRole ur join ur.user u join ur.role r join ur.department d join ur.application a"""
                        + where.text + " order by ur.expiresAt", Tuple.class))
                .setMaxResults(500).getResultList().stream()
                .map(t -> new ExpiringAssignmentRow(
                        t.get("userId", Long.class), t.get("userName", String.class), t.get("externalId", String.class),
                        t.get("roleId", Long.class), t.get("roleName", String.class), t.get("roleCode", String.class),
                        t.get("departmentId", Long.class), t.get("departmentName", String.class),
                        t.get("applicationId", Long.class), t.get("applicationName", String.class),
                        t.get("expiresAt", Instant.class), t.get("grantedBy", String.class), t.get("grantedAt", Instant.class)))
                .toList();
    }
}
