package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AccessReview;
import com.melonhub.rbac.infrastructure.persistence.entity.AccessReviewItem;
import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AccessReviewItemRepositoryImpl implements AccessReviewItemRepositoryCustom {
    private final EntityManager em;

    AccessReviewItemRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public int insertSnapshot(long reviewId, long applicationId, Long departmentId) {
        var where = new JpaQuery.Where()
                .and("ur.applicationId = ?", applicationId)
                .and("(ur.expiresAt is null or ur.expiresAt > current_timestamp)");
        if (departmentId != null) where.and("ur.departmentId = ?", departmentId);
        var assignments = where.bind(em.createQuery("""
                select u.id, r.id, ur.departmentId, ur.grantId, u.name, u.externalId, r.code, r.name, d.name
                from UserRole ur join ur.user u join ur.role r join ur.department d""" + where.text
                + " order by ur.departmentId, u.id, r.id", Tuple.class)).getResultList();
        var owner = em.getReference(AccessReview.class, reviewId);
        int count = 0;
        for (var t : assignments) {
            em.persist(new AccessReviewItem()
                    .setReview(owner)
                    .setUser(em.getReference(User.class, t.get(0, Long.class)))
                    .setRole(em.getReference(Role.class, t.get(1, Long.class)))
                    .setDepartmentId(t.get(2, Long.class))
                    .setGrantId(t.get(3, String.class))
                    .setUserName(t.get(4, String.class))
                    .setExternalId(t.get(5, String.class))
                    .setRoleCode(t.get(6, String.class))
                    .setRoleName(t.get(7, String.class))
                    .setDepartmentName(t.get(8, String.class))
                    .setDecision("PENDING")
                    .setComment(""));
            if (++count % 500 == 0) em.flush();
        }
        em.flush();
        return count;
    }

    @Override
    public Page<ReviewItemRow> findItemsPage(long reviewId, String decision, int page, int size) {
        var where = new JpaQuery.Where().and("i.review.id = ?", reviewId);
        if (decision != null && !decision.isBlank()) where.and("i.decision = ?", decision.strip());
        return JpaQuery.page(em, """
                select i.id as id, u.id as userId, coalesce(u.name, i.userName) as userName,
                       coalesce(u.externalId, i.externalId) as externalId, r.id as roleId,
                       coalesce(r.name, i.roleName) as roleName, coalesce(r.code, i.roleCode) as roleCode,
                       i.departmentId as departmentId, coalesce(d.name, i.departmentName) as departmentName,
                       i.decision as decision, i.comment as comment, i.decidedBy as decidedBy,
                       i.decidedAt as decidedAt, i.applied as applied, i.skipped as skipped""",
                "from AccessReviewItem i left join i.user u left join i.role r left join i.department d",
                where, "i.id desc", page, size,
                t -> new ReviewItemRow(
                        t.get("id", Long.class),
                        t.get("userId", Long.class),
                        t.get("userName", String.class),
                        t.get("externalId", String.class),
                        t.get("roleId", Long.class),
                        t.get("roleName", String.class),
                        t.get("roleCode", String.class),
                        t.get("departmentId", Long.class),
                        t.get("departmentName", String.class),
                        t.get("decision", String.class),
                        t.get("comment", String.class),
                        t.get("decidedBy", String.class),
                        t.get("decidedAt", Instant.class),
                        Boolean.TRUE.equals(t.get("applied")),
                        Boolean.TRUE.equals(t.get("skipped"))));
    }
}
