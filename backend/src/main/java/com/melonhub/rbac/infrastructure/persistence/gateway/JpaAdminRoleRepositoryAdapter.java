package com.melonhub.rbac.infrastructure.persistence.gateway;

import com.melonhub.rbac.infrastructure.persistence.repository.AdminFunctionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminRoleRepository;
import com.melonhub.rbac.service.ddd.domain.model.AdminRoleAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.AdminRoleRepositoryPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** DDD 基础设施适配器：将 {@link AdminRoleRepositoryPort} 桥接到 Spring Data JPA 仓储。 */
@Repository
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class JpaAdminRoleRepositoryAdapter implements AdminRoleRepositoryPort {
    private final AdminRoleRepository roles;
    private final AdminFunctionRepository functions;

    public JpaAdminRoleRepositoryAdapter(AdminRoleRepository roles, AdminFunctionRepository functions) {
        this.roles = roles;
        this.functions = functions;
    }

    @Override
    public List<FunctionRecord> functions() {
        return functions.findAllByOrderBySortOrder().stream()
                .map(f -> new FunctionRecord(f.getCode(), f.getName(), f.getCategory()))
                .toList();
    }

    @Override
    public List<RoleListRecord> roles() {
        var usage = new HashMap<String, Long>();
        for (var row : roles.usage()) usage.put(row.getRole(), row.getAccounts());
        return roles.listed().stream()
                .map(r -> new RoleListRecord(r.getCode(), r.getName(), r.getDescription(), r.isBuiltin(),
                        r.getFunctions().stream().sorted().toList(),
                        usage.getOrDefault(r.getCode(), 0L), r.getCreatedAt()))
                .toList();
    }

    @Override
    public Optional<AdminRoleAggregate> findRole(String code) {
        return roles.withFunctions(code)
                .map(r -> new AdminRoleAggregate(r.getCode(), r.getName(), r.getDescription(), r.isBuiltin(), Set.copyOf(r.getFunctions())));
    }

    @Override public boolean roleExists(String code) { return roles.existsById(code); }

    @Override
    public Map<String, Set<String>> functionsByRole() {
        var result = new HashMap<String, Set<String>>();
        for (var role : roles.listed()) result.put(role.getCode(), Set.copyOf(role.getFunctions()));
        return result;
    }

    @Override public long countAccountsUsingRole(String code) { return roles.accounts(code); }
    @Override public void createRole(String code, String name, String description, Collection<String> functionCodes) { roles.createRole(code, name, description, functionCodes); }
    @Override public void updateRole(String code, String name, String description, Collection<String> functionCodes) { roles.updateRole(code, name, description, functionCodes); }
    @Override public void deleteRole(String code) { roles.deleteRole(code); }
}
