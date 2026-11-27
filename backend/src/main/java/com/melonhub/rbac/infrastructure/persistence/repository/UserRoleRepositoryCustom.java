package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.service.port.security.Visibility;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 用户角色分配的加锁读、幂等授予、权限反查与即将到期报表片段。 */
public interface UserRoleRepositoryCustom {
    record PermissionHolderRow(long userId, String userName, String externalId, long departmentId, String departmentName,
                               long roleId, String roleName, String roleCode, Instant expiresAt) {}

    record ExpiringAssignmentRow(long userId, String userName, String externalId, long roleId, String roleName,
                                 String roleCode, long departmentId, String departmentName, long applicationId,
                                 String applicationName, Instant expiresAt, String grantedBy, Instant grantedAt) {}

    Map<Long, Instant> findLockedAssignments(long userId, long departmentId, long applicationId);

    List<Long> findLockedRoleMemberIds(long roleId);

    void addRoleMembers(long roleId, long departmentId, long applicationId, Collection<Long> userIds, String grantedBy);

    void grantRole(long userId, long departmentId, long applicationId, long roleId, Instant expiresAt, String grantedBy);

    long countPermissionHolders(long applicationId, String permissionCode, Long departmentId,
                                Collection<Long> visibleDepartments);

    List<PermissionHolderRow> findPermissionHolders(long applicationId, String permissionCode, Long departmentId,
                                                    Collection<Long> visibleDepartments, int limit);

    List<ExpiringAssignmentRow> findExpiringAssignments(Instant deadline, Visibility visible, Long applicationId);
}
