package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Department;
import com.melonhub.rbac.infrastructure.persistence.entity.Membership;
import com.melonhub.rbac.infrastructure.persistence.entity.MembershipId;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class MembershipRepositoryImpl implements MembershipRepositoryCustom {
    private final EntityManager em;

    MembershipRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<Long> findLockedDepartmentIds(long userId) {
        var query = em.createQuery("select m from Membership m where m.user.id = :user", Membership.class)
                .setParameter("user", userId);
        return JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_WRITE).stream()
                .map(m -> m.getId().getDepartmentId()).toList();
    }

    @Override
    public boolean existsLocked(long userId, long departmentId) {
        var query = em.createQuery("select m from Membership m where m.user.id = :user and m.department.id = :dept", Membership.class)
                .setParameter("user", userId).setParameter("dept", departmentId);
        return !JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_READ).isEmpty();
    }

    @Override
    public long countLocked(Collection<Long> userIds, long departmentId) {
        if (userIds.isEmpty()) return 0;
        var query = em.createQuery("select m from Membership m where m.department.id = :dept and m.user.id in :users", Membership.class)
                .setParameter("dept", departmentId).setParameter("users", userIds);
        return JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_READ).size();
    }

    @Override
    public void addIfAbsent(long userId, long departmentId) {
        var id = new MembershipId(userId, departmentId);
        if (em.find(Membership.class, id, LockModeType.PESSIMISTIC_WRITE) != null) return;
        em.persist(new Membership().setId(id)
                .setUser(em.getReference(User.class, userId))
                .setDepartment(em.getReference(Department.class, departmentId)));
        em.flush();
    }

    @Override
    public Page<MemberRow> findDepartmentMembersPage(long departmentId, String keyword, int page, int size) {
        var where = new JpaQuery.Where().and("m.department.id = ?", departmentId);
        if (keyword != null && !keyword.isBlank()) {
            String pattern = JpaQuery.like(keyword.strip());
            where.and("(u.name like ? escape '!' or u.externalId like ? escape '!' or u.email like ? escape '!')",
                    pattern, pattern, pattern);
        }
        return JpaQuery.page(em,
                "select u.id as id, u.name as name, u.externalId as externalId, u.email as email, u.enabled as enabled",
                "from Membership m join m.user u", where, "u.id", page, size,
                t -> new MemberRow(
                        t.get("id", Long.class), t.get("name", String.class),
                        t.get("externalId", String.class), t.get("email", String.class),
                        Boolean.TRUE.equals(t.get("enabled"))));
    }

    @Override
    public List<IdentityDepartmentUserRow> findIdentityDepartmentUsers(long departmentId, String keyword, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        var where = identityWhere(departmentId, keyword);
        return where.bind(em.createQuery("""
                        select u.externalId as userId, u.externalId as no, u.name as name,
                               d.id as departmentId, d.name as departmentName
                        from Membership m join m.user u join m.department d""" + where.text + " order by u.id", Tuple.class))
                .setFirstResult((int) Math.min(Integer.MAX_VALUE, (long) (safePage - 1) * safeSize))
                .setMaxResults(safeSize).getResultList().stream()
                .map(t -> new IdentityDepartmentUserRow(
                        t.get("userId", String.class), t.get("no", String.class), t.get("name", String.class),
                        t.get("departmentId", Long.class), t.get("departmentName", String.class)))
                .toList();
    }

    @Override
    public long countIdentityDepartmentUsers(long departmentId, String keyword) {
        var where = identityWhere(departmentId, keyword);
        return where.bind(em.createQuery("select count(m) from Membership m join m.user u join m.department d" + where.text, Long.class))
                .getSingleResult();
    }

    private static JpaQuery.Where identityWhere(long departmentId, String keyword) {
        var where = new JpaQuery.Where().and("d.id = ?", departmentId).and("u.enabled = true");
        if (keyword != null && !keyword.isBlank()) {
            String pattern = JpaQuery.like(keyword.strip());
            where.and("(u.externalId like ? escape '!' or u.name like ? escape '!')", pattern, pattern);
        }
        return where;
    }
}
