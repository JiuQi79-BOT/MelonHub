package com.melonhub.rbac.dao;

import com.melonhub.rbac.entity.IdentityCredential;
import com.melonhub.rbac.entity.IdentitySession;
import com.melonhub.rbac.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一身份接口所需的用户、组织、权限和令牌索引查询。
 */
@Repository
@Transactional
public class IdentityDao {

    /** 登录凭据行。 */
    public record CredentialRow(long userId, String username, String passwordHash) {
    }

    /** 用户身份行。 */
    public record UserRow(long id, String externalId, String name) {
    }

    /** 部门行。 */
    public record DepartmentRow(long id, String code, String name, Long parentId, boolean hasChildren) {
    }

    /** 部门用户行。 */
    public record DepartmentUserRow(String userId, String no, String name, long departmentId, String departmentName) {
    }

    private static final List<String> COURSE_AUDIT_TYPES =
            List.of("score_changed", "group_changed", "milestone_changed", "roster_changed");

    private final EntityManager entityManager;

    /**
     * 创建统一身份 DAO。
     *
     * @param entityManager JPA 实体管理器
     */
    public IdentityDao(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * 按登录名读取启用凭据。
     *
     * @param username 登录名
     * @return 凭据
     */
    public Optional<CredentialRow> credential(String username) {
        List<Tuple> rows = entityManager.createQuery(
                        "select c.userId as userId, c.username as username, c.passwordHash as passwordHash "
                                + "from IdentityCredential c join c.user u "
                                + "where c.username = :username and c.enabled = true and u.enabled = true",
                        Tuple.class
                )
                .setParameter("username", username)
                .setMaxResults(1)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Tuple row = rows.get(0);
        return Optional.of(new CredentialRow(
                row.get("userId", Long.class),
                row.get("username", String.class),
                row.get("passwordHash", String.class)
        ));
    }

    /**
     * 按统一身份用户标识查询用户。
     *
     * @param externalId 统一身份用户标识
     * @return 用户
     */
    public Optional<UserRow> userByExternalId(String externalId) {
        List<Tuple> rows = entityManager.createQuery(
                        "select u.id as id, u.externalId as externalId, u.name as name from User u "
                                + "where u.externalId = :externalId and u.enabled = true",
                        Tuple.class
                )
                .setParameter("externalId", externalId)
                .setMaxResults(1)
                .getResultList();
        return rows.stream().findFirst().map(IdentityDao::userRow);
    }

    /**
     * 按数据库用户主键查询用户。
     *
     * @param userId 用户主键
     * @return 用户
     */
    public Optional<UserRow> userById(long userId) {
        List<Tuple> rows = entityManager.createQuery(
                        "select u.id as id, u.externalId as externalId, u.name as name from User u "
                                + "where u.id = :userId and u.enabled = true",
                        Tuple.class
                )
                .setParameter("userId", userId)
                .setMaxResults(1)
                .getResultList();
        return rows.stream().findFirst().map(IdentityDao::userRow);
    }

    /**
     * 读取用户在指定软件中的有效角色编码。
     *
     * @param userId 用户主键
     * @param applicationCode 软件编码
     * @return 角色编码
     */
    public List<String> roles(long userId, String applicationCode) {
        var roles = effectiveRoleIds(userId, applicationCode);
        if (roles.isEmpty()) return List.of();
        return entityManager.createQuery("select distinct r.code from Role r where r.id in :roles and r.enabled = true order by r.code", String.class)
                .setParameter("roles", roles).getResultList();
    }

    /**
     * 读取用户在指定软件中的有效权限编码。
     *
     * @param userId 用户主键
     * @param applicationCode 软件编码
     * @return 权限编码
     */
    public List<String> permissions(long userId, String applicationCode) {
        var roles = effectiveRoleIds(userId, applicationCode);
        if (roles.isEmpty()) return List.of();
        return entityManager.createQuery("""
select distinct p.code from RolePermission rp join rp.permission p join rp.role r
                where rp.role.id in :roles and r.enabled = true order by p.code""", String.class)
                .setParameter("roles", roles).getResultList();
    }

    /** 读取有效查看权限所在的部门，避免把单部门授权用于整个软件。 */
    public List<Long> permissionDepartments(long userId, String applicationCode, String permission) {
        var roles = effectiveRoleIds(userId, applicationCode);
        if (roles.isEmpty()) return List.of();
        return entityManager.createQuery("""
select distinct p.department.id from RolePermission rp join rp.permission p
                where rp.role.id in :roles and p.code = :permission order by p.department.id""", Long.class)
                .setParameter("roles", roles)
                .setParameter("permission", permission).getResultList();
    }

    private List<Long> effectiveRoleIds(long userId, String applicationCode) {
        var direct = entityManager.createQuery("""
select ur.role.id, ur.departmentId, ur.application.id from UserRole ur join ur.role r join r.application a
                where ur.user.id = :user and a.code = :app and a.enabled = true and r.enabled = true
                and (ur.expiresAt is null or ur.expiresAt > current_timestamp)""", Tuple.class)
                .setParameter("user", userId).setParameter("app", applicationCode).getResultList();
        var result = new LinkedHashSet<Long>();
        for (Tuple row : direct) {
            long root = row.get(0, Long.class), dept = row.get(1, Long.class), app = row.get(2, Long.class);
            var parents = new HashMap<Long, List<Long>>();
            for (Tuple edge : entityManager.createQuery("""
select ri.id.childRoleId, ri.id.parentRoleId from RoleInheritance ri
                    where ri.childRole.department.id = :dept and ri.childRole.application.id = :app""", Tuple.class)
                    .setParameter("dept", dept).setParameter("app", app).getResultList())
                parents.computeIfAbsent(edge.get(0, Long.class), k -> new ArrayList<>()).add(edge.get(1, Long.class));
            var pending = new ArrayList<Long>(); pending.add(root);
            while (!pending.isEmpty()) {
                long role = pending.remove(pending.size() - 1);
                if (!result.add(role)) continue;
                pending.addAll(parents.getOrDefault(role, List.of()));
            }
        }
        return new ArrayList<>(result);
    }

    /** 在授权部门内查询课程业务事件及操作人的资料。 */
    public RbacDao.Page courseAuditEvents(String applicationCode, List<Long> departments, String actor, Long actorId,
                                        String target, Instant from, Instant to, int page, int pageSize) {
        Jpql.Where where = new Jpql.Where()
                .and("a.code = ?", applicationCode)
                .in("e.departmentId", departments)
                .in("e.action", COURSE_AUDIT_TYPES);
        if (actor != null && !actor.isBlank()) {
            String pattern = Jpql.like(actor.strip());
            where.and("(e.actor like ? escape '!' or u.name like ? escape '!')", pattern, pattern);
        }
        if (actorId != null) where.and("u.id = ?", actorId);
        if (target != null && !target.isBlank()) where.and("e.target like ? escape '!'", Jpql.like(target.strip()));
        if (from != null) where.and("e.createdAt >= ?", from);
        if (to != null) where.and("e.createdAt <= ?", to);
        return Jpql.page(entityManager, """
                select e.id as id, e.createdAt as at, e.action as type, e.target as target, e.actor as actorNo,
                       u.id as actorId, u.name as actorName
                """, "from AuditEvent e join e.application a left join User u on u.externalId = e.actor",
                where, "e.createdAt desc, e.id desc", page, pageSize);
    }

    /** 批量读取当前角色，限制在查询者有权查看的部门。 */
    public Map<Long, List<String>> auditActorRoles(Collection<Long> userIds, String applicationCode, List<Long> departments) {
        if (userIds.isEmpty()) return Map.of();
        Map<Long, List<String>> result = new HashMap<>();
        List<Tuple> rows = entityManager.createQuery("""
                select distinct ur.user.id as userId, r.code as code from UserRole ur join ur.role r join r.application a
                where ur.user.id in :userIds and a.code = :applicationCode and a.enabled = true and r.enabled = true
                  and ur.departmentId in :departments and (ur.expiresAt is null or ur.expiresAt > current_timestamp)
                order by r.code
                """, Tuple.class)
                .setParameter("userIds", userIds)
                .setParameter("applicationCode", applicationCode)
                .setParameter("departments", departments)
                .getResultList();
        for (Tuple row : rows) {
            result.computeIfAbsent(row.get("userId", Long.class), ignored -> new ArrayList<>())
                    .add(row.get("code", String.class).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /**
     * 分页读取部门。
     *
     * @param keyword 部门名称或编码关键字
     * @param parentId 父部门
     * @param page 页码
     * @param pageSize 页大小
     * @return 部门行
     */
    public List<DepartmentRow> departments(String keyword, Long parentId, int page, int pageSize) {
        StringBuilder jpql = new StringBuilder(
                "select d.id as id, d.code as code, d.name as name, d.parent.id as parentId "
                        + "from Department d where 1 = 1"
        );
        if (parentId == null) {
            jpql.append(" and d.parent is null");
        } else {
            jpql.append(" and d.parent.id = :parentId");
        }
        if (keyword != null && !keyword.isBlank()) {
            jpql.append(" and (d.name like :keyword or d.code like :keyword)");
        }
        jpql.append(" order by d.id");
        jakarta.persistence.TypedQuery<Tuple> query = entityManager.createQuery(jpql.toString(), Tuple.class);
        if (parentId != null) {
            query.setParameter("parentId", parentId);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.setParameter("keyword", "%" + keyword.strip() + "%");
        }
        List<Tuple> rows = query.setFirstResult((page - 1) * pageSize).setMaxResults(pageSize).getResultList();
        Set<Long> departmentIds = new LinkedHashSet<>();
        for (Tuple row : rows) {
            departmentIds.add(row.get("id", Long.class));
        }
        Set<Long> parents = new LinkedHashSet<>();
        if (!departmentIds.isEmpty()) {
            parents.addAll(entityManager.createQuery(
                            "select distinct d.parent.id from Department d where d.parent.id in :ids",
                            Long.class
                    )
                    .setParameter("ids", departmentIds)
                    .getResultList());
        }
        return rows.stream().map(row -> new DepartmentRow(
                row.get("id", Long.class),
                row.get("code", String.class),
                row.get("name", String.class),
                row.get("parentId", Long.class),
                parents.contains(row.get("id", Long.class))
        )).toList();
    }

    /**
     * 统计部门数量。
     *
     * @param keyword 部门名称或编码关键字
     * @param parentId 父部门
     * @return 数量
     */
    public long departmentCount(String keyword, Long parentId) {
        StringBuilder jpql = new StringBuilder("select count(d) from Department d where 1 = 1");
        if (parentId == null) {
            jpql.append(" and d.parent is null");
        } else {
            jpql.append(" and d.parent.id = :parentId");
        }
        if (keyword != null && !keyword.isBlank()) {
            jpql.append(" and (d.name like :keyword or d.code like :keyword)");
        }
        jakarta.persistence.TypedQuery<Long> query = entityManager.createQuery(jpql.toString(), Long.class);
        if (parentId != null) {
            query.setParameter("parentId", parentId);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.setParameter("keyword", "%" + keyword.strip() + "%");
        }
        return query.getSingleResult();
    }

    /**
     * 查询部门是否存在。
     *
     * @param departmentId 部门主键
     * @return 是否存在
     */
    public boolean departmentExists(long departmentId) {
        return entityManager.createQuery("select count(d) from Department d where d.id = :id", Long.class)
                .setParameter("id", departmentId)
                .getSingleResult() > 0;
    }

    /**
     * 分页查询部门成员。
     *
     * @param departmentId 部门主键
     * @param keyword 用户标识或姓名关键字
     * @param page 页码
     * @param pageSize 页大小
     * @return 部门用户
     */
    public List<DepartmentUserRow> departmentUsers(long departmentId, String keyword, int page, int pageSize) {
        StringBuilder jpql = new StringBuilder(
                "select u.externalId as userId, u.externalId as no, u.name as name, d.id as departmentId, d.name as departmentName "
                        + "from Membership m join m.user u join m.department d where d.id = :departmentId and u.enabled = true"
        );
        if (keyword != null && !keyword.isBlank()) {
            jpql.append(" and (u.externalId like :keyword or u.name like :keyword)");
        }
        jpql.append(" order by u.id");
        jakarta.persistence.TypedQuery<Tuple> query = entityManager.createQuery(jpql.toString(), Tuple.class)
                .setParameter("departmentId", departmentId);
        if (keyword != null && !keyword.isBlank()) {
            query.setParameter("keyword", "%" + keyword.strip() + "%");
        }
        return query.setFirstResult((page - 1) * pageSize).setMaxResults(pageSize).getResultList().stream()
                .map(row -> new DepartmentUserRow(
                        row.get("userId", String.class),
                        row.get("no", String.class),
                        row.get("name", String.class),
                        row.get("departmentId", Long.class),
                        row.get("departmentName", String.class)
                ))
                .toList();
    }

    /**
     * 统计部门成员数量。
     *
     * @param departmentId 部门主键
     * @param keyword 用户标识或姓名关键字
     * @return 数量
     */
    public long departmentUserCount(long departmentId, String keyword) {
        StringBuilder jpql = new StringBuilder(
                "select count(m) from Membership m join m.user u where m.department.id = :departmentId and u.enabled = true"
        );
        if (keyword != null && !keyword.isBlank()) {
            jpql.append(" and (u.externalId like :keyword or u.name like :keyword)");
        }
        jakarta.persistence.TypedQuery<Long> query = entityManager.createQuery(jpql.toString(), Long.class)
                .setParameter("departmentId", departmentId);
        if (keyword != null && !keyword.isBlank()) {
            query.setParameter("keyword", "%" + keyword.strip() + "%");
        }
        return query.getSingleResult();
    }

    /**
     * 保存或更新登录凭据。
     *
     * @param userId 用户主键
     * @param username 登录名
     * @param passwordHash BCrypt 摘要
     */
    public void upsertCredential(long userId, String username, String passwordHash) {
        IdentityCredential credential = entityManager.find(IdentityCredential.class, userId);
        if (credential == null) {
            entityManager.persist(new IdentityCredential(userId, username, passwordHash, true));
        } else {
            credential.setUsername(username).setPasswordHash(passwordHash).setEnabled(true);
        }
        entityManager.flush();
    }

    /** 外部标识变化后，统一身份登录名跟随用户资料。 */
    public void renameCredential(long userId, String username) {
        entityManager.createQuery("update IdentityCredential c set c.username = :username where c.userId = :userId")
                .setParameter("username", username)
                .setParameter("userId", userId)
                .executeUpdate();
        entityManager.clear();
    }

    /** 在用户写锁内撤销统一身份会话。 */
    public void revokeUserSessions(long userId) {
        entityManager.createQuery("update IdentitySession s set s.revokedAt = :now "
                        + "where s.user.id = :userId and s.revokedAt is null")
                .setParameter("now", Instant.now())
                .setParameter("userId", userId)
                .executeUpdate();
        entityManager.clear();
    }

    /**
     * 建立 JWT 会话索引。
     *
     * @param tokenHash JWT 摘要
     * @param verified 已校验的凭据
     * @param audience 目标系统
     * @param expiresAt 过期时间
     */
    public boolean createSession(String tokenHash, CredentialRow verified, String audience, Instant expiresAt) {
        // 与改密、停用和改名使用相同的用户行锁，防止并发登录用旧凭据建立会话。
        User user = entityManager.find(User.class, verified.userId(), jakarta.persistence.LockModeType.PESSIMISTIC_READ);
        if (user == null || !user.isEnabled() || !user.getExternalId().equals(verified.username())) return false;
        IdentityCredential current = entityManager.find(IdentityCredential.class, verified.userId(),
                jakarta.persistence.LockModeType.PESSIMISTIC_READ);
        if (current == null || !current.isEnabled() || !current.getUsername().equals(verified.username())
                || !current.getPasswordHash().equals(verified.passwordHash())) return false;
        entityManager.persist(new IdentitySession(tokenHash, user, audience, expiresAt));
        entityManager.flush();
        return true;
    }

    /**
     * 判断 JWT 会话是否仍然有效。
     *
     * @param tokenHash JWT 摘要
     * @param now 当前时间
     * @return 是否有效
     */
    public boolean activeSession(String tokenHash, Instant now) {
        return entityManager.createQuery(
                        "select count(s) from IdentitySession s where s.tokenHash = :tokenHash "
                                + "and s.revokedAt is null and s.expiresAt > :now and s.user.enabled = true",
                        Long.class
                )
                .setParameter("tokenHash", tokenHash)
                .setParameter("now", now)
                .getSingleResult() > 0;
    }

    /**
     * 撤销 JWT 会话。
     *
     * @param tokenHash JWT 摘要
     * @param revokedAt 撤销时间
     */
    public void revokeSession(String tokenHash, Instant revokedAt) {
        entityManager.createQuery(
                        "update IdentitySession s set s.revokedAt = :revokedAt "
                                + "where s.tokenHash = :tokenHash and s.revokedAt is null"
                )
                .setParameter("revokedAt", revokedAt)
                .setParameter("tokenHash", tokenHash)
                .executeUpdate();
        entityManager.clear();
    }

    private static UserRow userRow(Tuple row) {
        return new UserRow(row.get("id", Long.class), row.get("externalId", String.class), row.get("name", String.class));
    }
}
