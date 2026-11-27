package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AccessRequest;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AccessRequestRepository
        extends JpaRepository<AccessRequest, Long>, AccessRequestRepositoryCustom {

    @Query("select count(r) > 0 from AccessRequest r where r.user.id = :userId and r.role.id = :roleId and r.status = 'PENDING'")
    boolean existsPending(long userId, long roleId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AccessRequest r set r.status = 'CANCELLED', r.pendingRoleId = null, r.decidedBy = 'system',
                   r.decidedAt = current_timestamp, r.decisionComment = :comment
            where r.user.id = :userId and r.status = 'PENDING'""")
    int cancelPendingForUser(long userId, String comment);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AccessRequest r set r.status = 'CANCELLED', r.pendingRoleId = null, r.decidedBy = 'system',
                   r.decidedAt = current_timestamp, r.decisionComment = :comment
            where r.role.id = :roleId and r.status = 'PENDING'""")
    int cancelPendingForRole(long roleId, String comment);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AccessRequest r set r.status = :status, r.pendingRoleId = null, r.decidedBy = :decidedBy,
                   r.decidedAt = current_timestamp, r.decisionComment = :comment, r.grantExpiresAt = :grantExpiresAt
            where r.id = :id and r.status = 'PENDING'""")
    int decidePending(long id, String status, String decidedBy, String comment, Instant grantExpiresAt);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AccessRequest r set r.status = 'CANCELLED', r.pendingRoleId = null, r.decidedAt = current_timestamp
            where r.id = :id and r.user.id = :userId and r.status = 'PENDING'""")
    int cancelOwnPending(long id, long userId);
}
