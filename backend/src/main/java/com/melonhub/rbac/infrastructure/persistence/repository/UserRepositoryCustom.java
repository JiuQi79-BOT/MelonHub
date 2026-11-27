package com.melonhub.rbac.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;

/** 用户的加锁读、动态分页筛选与模糊搜索仓储片段。 */
public interface UserRepositoryCustom {
    record UserRow(long id, String name, String externalId, String email, boolean enabled,
                   Long activeDepartmentId, long contextVersion, Instant createdAt) {}

    record LockedUserRow(long id, boolean enabled, Long activeDepartmentId, long contextVersion) {}

    record SearchRow(long id, String name, String externalId, boolean enabled) {}

    Optional<String> lockExternalIdById(long id);

    List<Long> lockIdsByIdIn(Collection<Long> ids);

    Optional<Long> lockIdByExternalId(String externalId);

    Optional<LockedUserRow> lockRowByExternalId(String externalId);

    Page<UserRow> findPage(String keyword, Long departmentId, Boolean enabled,
                           Collection<Long> visibleDepartments, int page, int size);

    List<SearchRow> searchVisible(String keyword, Collection<Long> visibleDepartments);
}
