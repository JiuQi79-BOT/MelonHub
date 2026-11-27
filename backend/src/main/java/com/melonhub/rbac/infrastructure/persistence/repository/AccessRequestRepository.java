package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AccessRequest;
import com.melonhub.rbac.infrastructure.persistence.entity.Application;
import com.melonhub.rbac.infrastructure.persistence.entity.Department;
import com.melonhub.rbac.infrastructure.persistence.entity.Role;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import com.melonhub.rbac.service.port.security.Visibility;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, Long> {
    record AccessRequestRow(long id, Long userId, String userName, String externalId, Long departmentId,
                            String departmentName, long applicationId, String applicationName, Long roleId,
                            String roleName, String roleCode, Boolean roleEnabled, String reason,
                            Integer durationDays, String status, String requestedBy, String decidedBy,
                            Instant decidedAt, String decisionComment, Instant grantExpiresAt,
                            Instant createdAt) {}

    interface RequestProjection {
        long getId();
        Long getUserId();
        String getUserName();
        String getExternalId();
        Long getDepartmentId();
        String getDepartmentName();
        long getApplicationId();
        String getApplicationName();
        Long getRoleId();
        String getRoleName();
        String getRoleCode();
        Boolean getRoleEnabled();
        String getReason();
        Integer getDurationDays();
        String getStatus();
        String getRequestedBy();
        String getDecidedBy();
        Instant getDecidedAt();
        String getDecisionComment();
        Instant getGrantExpiresAt();
        Instant getCreatedAt();
    }

    String REQUEST_SELECT = """
            select r.id as id, u.id as userId, coalesce(u.name, r.userName) as userName,
                   coalesce(u.externalId, r.externalId) as externalId, d.id as departmentId,
                   coalesce(d.name, r.departmentName) as departmentName, a.id as applicationId,
                   a.name as applicationName, ro.id as roleId, coalesce(ro.name, r.roleName) as roleName,
                   coalesce(ro.code, r.roleCode) as roleCode, ro.enabled as roleEnabled,
                   r.reason as reason, r.durationDays as durationDays, r.status as status,
                   r.requestedBy as requestedBy, r.decidedBy as decidedBy, r.decidedAt as decidedAt,
                   r.decisionComment as decisionComment, r.grantExpiresAt as grantExpiresAt, r.createdAt as createdAt
            from AccessRequest r left join r.user u left join r.department d join r.application a left join r.role ro
            """;

    @Query("select count(r) > 0 from AccessRequest r where r.user.id = :userId and r.role.id = :roleId and r.status = 'PENDING'")
    boolean existsPending(long userId, long roleId);

    @Query(REQUEST_SELECT + "where r.id = :id")
    Optional<RequestProjection> findDetailProjectionById(long id);

    @Query(value = REQUEST_SELECT + """
            where (:applicationId is null or r.application.id = :applicationId)
              and (:filterApps = false or r.application.id in :appIds)
              and (:filterDepts = false or r.department.id in :deptIds)
              and (:status is null or r.status = :status)
              and (:pattern is null
                   or coalesce(u.name, r.userName) like :pattern escape '!'
                   or coalesce(u.externalId, r.externalId) like :pattern escape '!'
                   or coalesce(ro.name, r.roleName) like :pattern escape '!'
                   or coalesce(ro.code, r.roleCode) like :pattern escape '!')
            order by r.id desc""",
            countQuery = """
            select count(r) from AccessRequest r left join r.user u join r.application a left join r.role ro
            where (:applicationId is null or r.application.id = :applicationId)
              and (:filterApps = false or r.application.id in :appIds)
              and (:filterDepts = false or r.department.id in :deptIds)
              and (:status is null or r.status = :status)
              and (:pattern is null
                   or coalesce(u.name, r.userName) like :pattern escape '!'
                   or coalesce(u.externalId, r.externalId) like :pattern escape '!'
                   or coalesce(ro.name, r.roleName) like :pattern escape '!'
                   or coalesce(ro.code, r.roleCode) like :pattern escape '!')""")
    Page<RequestProjection> findPageProjections(Long applicationId, boolean filterApps, Collection<Long> appIds,
                                                boolean filterDepts, Collection<Long> deptIds,
                                                String status, String pattern, Pageable pageable);

    @Query(REQUEST_SELECT + "where r.user.id = :userId and r.application.id = :applicationId order by r.id desc")
    List<RequestProjection> findRecentProjections(long userId, long applicationId, Limit limit);

    @Query("""
            select count(r) from AccessRequest r
            where r.status = 'PENDING'
              and (:filterApps = false or r.application.id in :appIds)
              and (:filterDepts = false or r.department.id in :deptIds)""")
    long countPendingRows(boolean filterApps, Collection<Long> appIds, boolean filterDepts, Collection<Long> deptIds);

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

    @Transactional
    default long createRequest(User person, Department department, Application application, Role granted,
                               String reason, Integer durationDays, String requestedBy) {
        return saveAndFlush(new AccessRequest()
                .setUser(person)
                .setDepartment(department)
                .setApplication(application)
                .setRole(granted)
                .setPendingRoleId(granted.getId())
                .setReason(reason)
                .setDurationDays(durationDays)
                .setStatus("PENDING")
                .setRequestedBy(requestedBy)
                .setDecisionComment("")
                .setUserName(person.getName())
                .setExternalId(person.getExternalId())
                .setRoleCode(granted.getCode())
                .setRoleName(granted.getName())
                .setDepartmentName(department.getName())).getId();
    }

    default Optional<AccessRequestRow> findDetailById(long id) {
        return findDetailProjectionById(id).map(AccessRequestRepository::toRow);
    }

    default Page<AccessRequestRow> findPage(Long applicationId, Visibility visible, String status,
                                            String keyword, int page, int size) {
        var pageable = RepositorySupport.page(page, size);
        boolean filterApps = visible.applications() != null;
        boolean filterDepts = visible.departments() != null;
        if ((filterApps && visible.applications().isEmpty()) || (filterDepts && visible.departments().isEmpty())) {
            return Page.empty(pageable);
        }
        return findPageProjections(applicationId,
                filterApps, filterApps ? visible.applications() : RepositorySupport.NO_IDS,
                filterDepts, filterDepts ? visible.departments() : RepositorySupport.NO_IDS,
                RepositorySupport.textOrNull(status), RepositorySupport.likeOrNull(keyword), pageable)
                .map(AccessRequestRepository::toRow);
    }

    default List<AccessRequestRow> findRecentByUserAndApplication(long userId, long applicationId, int limit) {
        return findRecentProjections(userId, applicationId, Limit.of(limit)).stream()
                .map(AccessRequestRepository::toRow)
                .toList();
    }

    default long countPending(Visibility visible) {
        boolean filterApps = visible.applications() != null;
        boolean filterDepts = visible.departments() != null;
        if ((filterApps && visible.applications().isEmpty()) || (filterDepts && visible.departments().isEmpty())) {
            return 0L;
        }
        return countPendingRows(filterApps, filterApps ? visible.applications() : RepositorySupport.NO_IDS,
                filterDepts, filterDepts ? visible.departments() : RepositorySupport.NO_IDS);
    }

    private static AccessRequestRow toRow(RequestProjection p) {
        return new AccessRequestRow(
                p.getId(), p.getUserId(), p.getUserName(), p.getExternalId(), p.getDepartmentId(),
                p.getDepartmentName(), p.getApplicationId(), p.getApplicationName(), p.getRoleId(),
                p.getRoleName(), p.getRoleCode(), p.getRoleEnabled(), p.getReason(),
                p.getDurationDays(), p.getStatus(), p.getRequestedBy(), p.getDecidedBy(),
                p.getDecidedAt(), p.getDecisionComment(), p.getGrantExpiresAt(), p.getCreatedAt());
    }
}
