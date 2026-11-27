package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AccessRequest;
import com.melonhub.rbac.infrastructure.persistence.entity.Application;
import com.melonhub.rbac.infrastructure.persistence.entity.Department;
import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import com.melonhub.rbac.service.port.security.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AccessRequestRepositoryImpl implements AccessRequestRepositoryCustom {
    private static final String REQUEST_SELECT = """
            select r.id as id, u.id as userId, coalesce(u.name, r.userName) as userName,
                   coalesce(u.externalId, r.externalId) as externalId, d.id as departmentId,
                   coalesce(d.name, r.departmentName) as departmentName, a.id as applicationId,
                   a.name as applicationName, ro.id as roleId, coalesce(ro.name, r.roleName) as roleName,
                   coalesce(ro.code, r.roleCode) as roleCode, ro.enabled as roleEnabled,
                   r.reason as reason, r.durationDays as durationDays, r.status as status,
                   r.requestedBy as requestedBy, r.decidedBy as decidedBy, r.decidedAt as decidedAt,
                   r.decisionComment as decisionComment, r.grantExpiresAt as grantExpiresAt, r.createdAt as createdAt""";
    private static final String REQUEST_FROM =
            "from AccessRequest r left join r.user u left join r.department d join r.application a left join r.role ro";

    private final EntityManager em;

    AccessRequestRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public long createRequest(long userId, long departmentId, long applicationId, long roleId,
                              String reason, Integer durationDays, String requestedBy) {
        User person = em.find(User.class, userId);
        Role granted = em.find(Role.class, roleId);
        Department department = em.find(Department.class, departmentId);
        var request = new AccessRequest()
                .setUser(person)
                .setDepartment(department)
                .setApplication(em.getReference(Application.class, applicationId))
                .setRole(granted)
                .setPendingRoleId(roleId)
                .setReason(reason)
                .setDurationDays(durationDays)
                .setStatus("PENDING")
                .setRequestedBy(requestedBy)
                .setDecisionComment("")
                .setUserName(person.getName())
                .setExternalId(person.getExternalId())
                .setRoleCode(granted.getCode())
                .setRoleName(granted.getName())
                .setDepartmentName(department.getName());
        em.persist(request);
        em.flush();
        return request.getId();
    }

    @Override
    public Optional<AccessRequestRow> findDetailById(long id) {
        return JpaQuery.first(em.createQuery(REQUEST_SELECT + " " + REQUEST_FROM + " where r.id = :id", Tuple.class)
                .setParameter("id", id)).map(AccessRequestRepositoryImpl::toRow);
    }

    @Override
    public Page<AccessRequestRow> findPage(Long applicationId, Visibility visible, String status,
                                           String keyword, int page, int size) {
        var where = new JpaQuery.Where();
        if (applicationId != null) where.and("r.application.id = ?", applicationId);
        if (visible.applications() != null) where.in("r.application.id", visible.applications());
        if (visible.departments() != null) where.in("r.department.id", visible.departments());
        if (status != null && !status.isBlank()) where.and("r.status = ?", status.strip());
        if (keyword != null && !keyword.isBlank()) {
            String pattern = JpaQuery.like(keyword.strip());
            where.and("(coalesce(u.name, r.userName) like ? escape '!' or coalesce(u.externalId, r.externalId) like ? escape '!' "
                    + "or coalesce(ro.name, r.roleName) like ? escape '!' or coalesce(ro.code, r.roleCode) like ? escape '!')",
                    pattern, pattern, pattern, pattern);
        }
        return JpaQuery.page(em, REQUEST_SELECT, REQUEST_FROM, where, "r.id desc", page, size,
                AccessRequestRepositoryImpl::toRow);
    }

    @Override
    public List<AccessRequestRow> findRecentByUserAndApplication(long userId, long applicationId, int limit) {
        return em.createQuery(REQUEST_SELECT + " " + REQUEST_FROM
                        + " where r.user.id = :userId and r.application.id = :applicationId order by r.id desc", Tuple.class)
                .setParameter("userId", userId)
                .setParameter("applicationId", applicationId)
                .setMaxResults(limit)
                .getResultList().stream()
                .map(AccessRequestRepositoryImpl::toRow)
                .toList();
    }

    @Override
    public long countPending(Visibility visible) {
        var where = new JpaQuery.Where().and("r.status = 'PENDING'");
        if (visible.applications() != null) where.in("r.application.id", visible.applications());
        if (visible.departments() != null) where.in("r.department.id", visible.departments());
        return where.bind(em.createQuery("select count(r) from AccessRequest r" + where.text, Long.class))
                .getSingleResult();
    }

    private static AccessRequestRow toRow(Tuple t) {
        return new AccessRequestRow(
                t.get("id", Long.class),
                t.get("userId", Long.class),
                t.get("userName", String.class),
                t.get("externalId", String.class),
                t.get("departmentId", Long.class),
                t.get("departmentName", String.class),
                t.get("applicationId", Long.class),
                t.get("applicationName", String.class),
                t.get("roleId", Long.class),
                t.get("roleName", String.class),
                t.get("roleCode", String.class),
                t.get("roleEnabled", Boolean.class),
                t.get("reason", String.class),
                t.get("durationDays", Integer.class),
                t.get("status", String.class),
                t.get("requestedBy", String.class),
                t.get("decidedBy", String.class),
                t.get("decidedAt", Instant.class),
                t.get("decisionComment", String.class),
                t.get("grantExpiresAt", Instant.class),
                t.get("createdAt", Instant.class));
    }
}
