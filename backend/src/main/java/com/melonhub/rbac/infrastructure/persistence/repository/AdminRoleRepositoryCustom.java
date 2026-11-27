package com.melonhub.rbac.infrastructure.persistence.repository;

import java.util.Collection;

/** 管理员角色使用分配主键，需要显式 persist 新建与托管实体更新/删除。 */
public interface AdminRoleRepositoryCustom {
    void createRole(String code, String name, String description, Collection<String> functionCodes);

    void updateRole(String code, String name, String description, Collection<String> functionCodes);

    void deleteRole(String code);
}
