package com.melonhub.rbac.service.ddd.domain.repository;

import com.melonhub.rbac.service.ddd.domain.model.IdentityAccountAggregate;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 统一身份认证与课程联调限界上下文的领域仓储端口。
 */
public interface IdentityRepositoryPort {
    record DepartmentRecord(long id, String code, String name, Long parentId, boolean hasChildren) {}

    record DepartmentUserRecord(String userId, String no, String name, long departmentId, String departmentName) {}

    Optional<IdentityAccountAggregate.IdentityCredentialSnapshot> findCredential(String username);

    Optional<IdentityAccountAggregate.IdentityUser> findUserByExternalId(String externalId);

    Optional<Long> findAnyUserIdByExternalId(String externalId);

    boolean createSession(String tokenHash, IdentityAccountAggregate.IdentityCredentialSnapshot credential, String systemCode, Instant expiresAt);

    boolean hasActiveSession(String tokenHash, Instant now);

    void revokeSession(String tokenHash, Instant now);

    void upsertCredential(long userId, String username, String passwordHash);

    void renameCredential(long userId, String newUsername);

    void revokeUserSessions(long userId);

    List<String> roles(long userId, String systemCode);

    List<String> permissions(long userId, String systemCode);

    List<Long> permissionDepartments(long userId, String systemCode, String permissionCode);

    List<DepartmentRecord> departments(String keyword, Long parentId, int page, int pageSize);

    long departmentCount(String keyword, Long parentId);

    boolean departmentExists(long departmentId);

    List<DepartmentUserRecord> departmentUsers(long departmentId, String keyword, int page, int pageSize);

    long departmentUserCount(long departmentId, String keyword);

    PageSlice<IdentityAccountAggregate.CourseAuditRecord> courseAuditEvents(
            String systemCode, Collection<Long> departmentIds, String actor, Long actorId,
            String target, Instant from, Instant to, int page, int pageSize);

    Map<Long, List<String>> auditActorRoles(Collection<Long> actorIds, String systemCode, Collection<Long> departmentIds);
}
