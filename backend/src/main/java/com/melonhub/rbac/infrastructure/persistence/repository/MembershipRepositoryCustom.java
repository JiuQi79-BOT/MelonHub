package com.melonhub.rbac.infrastructure.persistence.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;

/** 部门成员关系的加锁读、幂等写入与分页查询片段。 */
public interface MembershipRepositoryCustom {
    record MemberRow(long id, String name, String externalId, String email, boolean enabled) {}

    record IdentityDepartmentUserRow(String userId, String no, String name, long departmentId, String departmentName) {}

    List<Long> findLockedDepartmentIds(long userId);

    boolean existsLocked(long userId, long departmentId);

    long countLocked(Collection<Long> userIds, long departmentId);

    void addIfAbsent(long userId, long departmentId);

    Page<MemberRow> findDepartmentMembersPage(long departmentId, String keyword, int page, int size);

    List<IdentityDepartmentUserRow> findIdentityDepartmentUsers(long departmentId, String keyword, int page, int size);

    long countIdentityDepartmentUsers(long departmentId, String keyword);
}
