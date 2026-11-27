package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.service.port.security.Visibility;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 角色的行锁读与动态可见范围模糊搜索片段。 */
public interface RoleRepositoryCustom {
    record SearchRow(long id, String name, String code, long departmentId, String departmentName,
                     long applicationId, String applicationName) {}

    boolean lockById(long roleId);

    /**
     * 仅按聚簇主键 {@code PRIMARY(id)} 升序锁定指定角色集合（不触碰二级索引，且作为写事务首条语句避免过早开启 ReadView），
     * 返回当前已提交且属于目标隔离域的角色 ID 集合。
     */
    Set<Long> lockDomainRolesByIds(long departmentId, long applicationId, Collection<Long> roleIds);

    List<SearchRow> searchVisible(String keyword, Visibility visible);
}
