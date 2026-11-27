package com.melonhub.rbac.service.structured.admin;

import com.melonhub.rbac.infrastructure.persistence.entity.AdminRole;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminFunctionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminRoleRepository;
import com.melonhub.rbac.service.port.admin.AdminModels.AdminFunctionResult;
import com.melonhub.rbac.service.port.admin.AdminModels.AdminRoleResult;
import com.melonhub.rbac.service.port.admin.AdminModels.CreateAdminRoleCommand;
import com.melonhub.rbac.service.port.admin.AdminModels.UpdateAdminRoleCommand;
import com.melonhub.rbac.service.port.admin.AdminRoleUseCase;
import com.melonhub.rbac.service.port.admin.AuthUseCase;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.security.AdminFunction;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structured 管理员功能清单与自定义管理员角色维护服务。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "structured", matchIfMissing = true)
public class StructuredAdminRoleService implements AdminRoleUseCase {
    private final AdminRoleRepository roleRepo;
    private final AdminFunctionRepository functionRepo;
    private final AuditUseCase audit;
    private final AuthUseCase accounts;

    public StructuredAdminRoleService(AdminRoleRepository roleRepo, AdminFunctionRepository functionRepo,
                                      AuditUseCase audit, AuthUseCase accounts) {
        this.roleRepo = roleRepo;
        this.functionRepo = functionRepo;
        this.audit = audit;
        this.accounts = accounts;
    }

    @Override
    public List<AdminFunctionResult> functions() {
        return functionRepo.findAllByOrderBySortOrder().stream()
                .map(f -> new AdminFunctionResult(f.getCode(), f.getName(), f.getCategory()))
                .toList();
    }

    @Override
    public List<AdminRoleResult> roles() {
        Map<String, Long> usage = new HashMap<>();
        for (AdminRoleRepository.RoleUsage row : roleRepo.usage()) {
            usage.put(row.getRole(), row.getAccounts());
        }
        return roleRepo.listed().stream()
                .map(r -> new AdminRoleResult(r.getCode(), r.getName(), r.getDescription(), r.isBuiltin(),
                        r.getFunctions().stream().sorted().toList(),
                        usage.getOrDefault(r.getCode(), 0L), r.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional
    public void create(CreateAdminRoleCommand input, AdminPrincipal actor) {
        Set<String> functions = functions(input.functions(), actor);
        if (roleRepo.existsById(input.code())) {
            throw ApiException.conflict("角色编码已存在");
        }
        roleRepo.createRole(input.code(), input.name().strip(), text(input.description()), functions);
        audit.audit(actor.actor(), "ADMIN_ROLE_CREATED", input.code(), null, null, "功能 " + String.join(", ", functions));
    }

    @Override
    @Transactional
    public void update(String code, UpdateAdminRoleCommand input, AdminPrincipal actor) {
        accounts.lockTree();
        AdminRole role = roleRepo.withFunctions(code).orElseThrow(() -> ApiException.missing("管理员角色不存在"));
        if (role.isBuiltin()) {
            throw ApiException.bad("内置角色不能修改");
        }
        Set<String> before = Set.copyOf(role.getFunctions());
        Set<String> functions = functions(input.functions(), actor);
        accounts.requireConsistentWithRole(code, functions);
        roleRepo.updateRole(code, input.name().strip(), text(input.description()), functions);
        audit.audit(actor.actor(), "ADMIN_ROLE_UPDATED", code, null, null, difference(before, functions));
    }

    @Override
    @Transactional
    public void delete(String code, AdminPrincipal actor) {
        accounts.lockTree();
        AdminRole role = roleRepo.withFunctions(code).orElseThrow(() -> ApiException.missing("管理员角色不存在"));
        if (role.isBuiltin()) {
            throw ApiException.bad("内置角色不能删除");
        }
        String roleName = role.getName();
        long count = roleRepo.accounts(code);
        if (count > 0) {
            throw ApiException.conflict("仍有 " + count + " 个管理员账号使用该角色，请先为这些账号更换角色");
        }
        roleRepo.deleteRole(code);
        audit.audit(actor.actor(), "ADMIN_ROLE_DELETED", code, null, null, roleName);
    }

    private static Set<String> functions(List<String> codes, AdminPrincipal actor) {
        Set<String> result = new TreeSet<>();
        for (String code : codes) {
            AdminFunction function = AdminFunction.of(code);
            if (function == null) {
                throw ApiException.bad("未知的管理功能: " + code);
            }
            if (function.platformAdminOnly()) {
                throw ApiException.forbidden("该功能仅属于超级管理员和平台管理员角色: " + code);
            }
            if (!actor.has(function)) {
                throw ApiException.forbidden("不能授予自己没有的管理功能: " + code);
            }
            result.add(code);
        }
        return result;
    }

    private static String difference(Set<String> before, Set<String> after) {
        Set<String> added = new TreeSet<>(after);
        added.removeAll(before);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(after);
        List<String> parts = new ArrayList<>();
        if (!added.isEmpty()) parts.add("新增功能 " + String.join(", ", added));
        if (!removed.isEmpty()) parts.add("移除功能 " + String.join(", ", removed));
        return String.join("；", parts);
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
