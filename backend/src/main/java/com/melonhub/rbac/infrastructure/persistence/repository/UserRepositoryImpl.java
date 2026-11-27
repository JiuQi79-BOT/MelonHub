package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class UserRepositoryImpl implements UserRepositoryCustom {
    private static final String USER_COLUMNS = """
            u.id as id, u.name as name, u.externalId as externalId, u.email as email, u.enabled as enabled,
            ad.id as activeDepartmentId, u.contextVersion as contextVersion, u.createdAt as createdAt""";

    private final EntityManager em;

    UserRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    private static UserRow userRow(Tuple t) {
        return new UserRow(
                t.get("id", Long.class),
                t.get("name", String.class),
                t.get("externalId", String.class),
                t.get("email", String.class),
                Boolean.TRUE.equals(t.get("enabled")),
                t.get("activeDepartmentId", Long.class),
                t.get("contextVersion", Long.class),
                t.get("createdAt", Instant.class));
    }

    private static void filterMembers(JpaQuery.Where where, Collection<Long> departments) {
        if (departments.isEmpty()) where.and("1=0");
        else where.and("exists (select 1 from Membership mv where mv.user.id = u.id and mv.department.id in ?)", departments);
    }

    @Override
    public Optional<String> lockExternalIdById(long id) {
        var query = em.createQuery("select u from User u where u.id = :id", User.class).setParameter("id", id);
        return JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_WRITE).stream()
                .findFirst().map(User::getExternalId);
    }

    @Override
    public List<Long> lockIdsByIdIn(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        var query = em.createQuery("select u from User u where u.id in :ids order by u.id", User.class)
                .setParameter("ids", ids);
        return JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_WRITE).stream()
                .map(User::getId).toList();
    }

    @Override
    public Optional<Long> lockIdByExternalId(String externalId) {
        var query = em.createQuery("select u from User u where u.externalId = :external", User.class)
                .setParameter("external", externalId);
        return JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_WRITE).stream()
                .findFirst().map(User::getId);
    }

    @Override
    public Optional<LockedUserRow> lockRowByExternalId(String externalId) {
        var query = em.createQuery("select u from User u where u.externalId = :external", User.class)
                .setParameter("external", externalId);
        return JpaQuery.lockedList(em, query, LockModeType.PESSIMISTIC_WRITE).stream()
                .findFirst()
                .map(user -> new LockedUserRow(
                        user.getId(),
                        user.isEnabled(),
                        user.getActiveDepartment() == null ? null : user.getActiveDepartment().getId(),
                        user.getContextVersion()));
    }

    @Override
    public Page<UserRow> findPage(String keyword, Long departmentId, Boolean enabled,
                                  Collection<Long> visibleDepartments, int page, int size) {
        var where = new JpaQuery.Where();
        if (visibleDepartments != null) filterMembers(where, visibleDepartments);
        if (enabled != null) where.and("u.enabled = ?", enabled);
        if (departmentId != null) {
            where.and("exists (select 1 from Membership m where m.user.id = u.id and m.department.id = ?)", departmentId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String pattern = JpaQuery.like(keyword.strip());
            where.and("(u.name like ? escape '!' or u.externalId like ? escape '!' or u.email like ? escape '!')",
                    pattern, pattern, pattern);
        }
        return JpaQuery.page(em, "select " + USER_COLUMNS, "from User u left join u.activeDepartment ad",
                where, "u.id", page, size, UserRepositoryImpl::userRow);
    }

    @Override
    public List<SearchRow> searchVisible(String keyword, Collection<Long> visibleDepartments) {
        String pattern = JpaQuery.like(keyword);
        var where = new JpaQuery.Where()
                .and("(u.name like ? escape '!' or u.externalId like ? escape '!' or u.email like ? escape '!')",
                        pattern, pattern, pattern);
        if (visibleDepartments != null) filterMembers(where, visibleDepartments);
        return where.bind(em.createQuery(
                        "select u.id as id, u.name as name, u.externalId as externalId, u.enabled as enabled from User u"
                                + where.text + " order by u.id", Tuple.class))
                .setMaxResults(5).getResultList().stream()
                .map(t -> new SearchRow(
                        t.get("id", Long.class), t.get("name", String.class),
                        t.get("externalId", String.class), Boolean.TRUE.equals(t.get("enabled"))))
                .toList();
    }
}
