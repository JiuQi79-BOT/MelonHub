package com.melonhub.rbac.service.ddd.application.admin;

import com.melonhub.rbac.service.ddd.application.shared.DddBoundary;
import com.melonhub.rbac.service.ddd.domain.model.AdminRoleAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.AdminRoleRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.port.admin.AdminModels.AdminFunctionResult;
import com.melonhub.rbac.service.port.admin.AdminModels.AdminRoleResult;
import com.melonhub.rbac.service.port.admin.AdminModels.CreateAdminRoleCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.UpdateAdminRoleCommand;
import com.melonhub.rbac.service.port.admin.AdminRoleUseCase;
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.security.AdminFunction;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DDD 管理员角色应用服务：将自定义角色校验与差分规则委托给 {@link AdminRoleAggregate}。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class DddAdminRoleService implements AdminRoleUseCase {
    private static final Set<String> KNOWN_FUNCTIONS = Arrays.stream(AdminFunction.values())
            .map(AdminFunction::code).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> PLATFORM_ADMIN_ONLY_FUNCTIONS = Arrays.stream(AdminFunction.values())
            .filter(AdminFunction::platformAdminOnly).map(AdminFunction::code).collect(Collectors.toUnmodifiableSet());

    private final AdminRoleRepositoryPort repository;
    private final AuditUseCase audit;
    private final AuthUseCase accounts;

    public DddAdminRoleService(AdminRoleRepositoryPort repository, AuditUseCase audit, AuthUseCase accounts) {
        this.repository = repository;
        this.audit = audit;
        this.accounts = accounts;
    }

    @Override
    public List<AdminFunctionResult> functions() {
        return repository.functions().stream()
                .map(f -> new AdminFunctionResult(f.code(), f.name(), f.category()))
                .toList();
    }

    @Override
    public List<AdminRoleResult> roles() {
        return repository.roles().stream()
                .map(r -> new AdminRoleResult(r.code(), r.name(), r.description(), r.builtin(), r.functions(), r.accountCount(), r.createdAt()))
                .toList();
    }

    @Override
    @Transactional
    public void create(CreateAdminRoleCommand input, AdminPrincipal actor) {
        DddBoundary.run(() -> {
            AdminRoleAggregate aggregate = AdminRoleAggregate.createCustom(
                    input.code(), input.name(), input.description(), input.functions(),
                    KNOWN_FUNCTIONS, heldFunctionCodes(actor), PLATFORM_ADMIN_ONLY_FUNCTIONS);
            if (repository.roleExists(aggregate.code())) {
                throw DomainException.conflict("角色编码已存在");
            }
            repository.createRole(aggregate.code(), aggregate.name(), aggregate.description(), aggregate.functions());
            audit.audit(actor.actor(), "ADMIN_ROLE_CREATED", aggregate.code(), null, null, aggregate.auditCreateDetail());
        });
    }

    @Override
    @Transactional
    public void update(String code, UpdateAdminRoleCommand input, AdminPrincipal actor) {
        DddBoundary.run(() -> {
            accounts.lockTree();
            AdminRoleAggregate existing = repository.findRole(code).orElseThrow(() -> DomainException.missing("管理员角色不存在"));
            AdminRoleAggregate updated = existing.updateCustom(
                    input.name(), input.description(), input.functions(),
                    KNOWN_FUNCTIONS, heldFunctionCodes(actor), PLATFORM_ADMIN_ONLY_FUNCTIONS);
            accounts.requireConsistentWithRole(code, updated.functions());
            repository.updateRole(updated.code(), updated.name(), updated.description(), updated.functions());
            audit.audit(actor.actor(), "ADMIN_ROLE_UPDATED", updated.code(), null, null, updated.auditUpdateDiffFrom(existing));
        });
    }

    @Override
    @Transactional
    public void delete(String code, AdminPrincipal actor) {
        DddBoundary.run(() -> {
            accounts.lockTree();
            AdminRoleAggregate existing = repository.findRole(code).orElseThrow(() -> DomainException.missing("管理员角色不存在"));
            existing.assertDeletable(repository.countAccountsUsingRole(code));
            repository.deleteRole(code);
            audit.audit(actor.actor(), "ADMIN_ROLE_DELETED", code, null, null, existing.name());
        });
    }

    private static Set<String> heldFunctionCodes(AdminPrincipal actor) {
        return actor.functions().stream().map(AdminFunction::code).collect(Collectors.toSet());
    }
}
