package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AdminRole;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AdminRoleRepositoryImpl implements AdminRoleRepositoryCustom {
    private final EntityManager em;

    AdminRoleRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public void createRole(String code, String name, String description, Collection<String> functionCodes) {
        // 分配主键不可使用 merge/save，避免静默覆盖已有角色。
        var role = new AdminRole(code, name, description);
        role.getFunctions().addAll(functionCodes);
        em.persist(role);
        em.flush();
    }

    @Override
    public void updateRole(String code, String name, String description, Collection<String> functionCodes) {
        AdminRole role = em.find(AdminRole.class, code);
        if (role == null) return;
        role.setName(name);
        role.setDescription(description);
        role.getFunctions().clear();
        role.getFunctions().addAll(functionCodes);
        em.flush();
    }

    @Override
    public void deleteRole(String code) {
        AdminRole role = em.find(AdminRole.class, code);
        if (role == null) return;
        em.remove(role);
        em.flush();
    }
}
