package com.melonhub.rbac.service.ddd.domain.model;

import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 控制台管理员角色聚合根：保护内置角色不可修改删除、平台管理员专属功能禁止写入自定义角色、
 * 不可授予自身不具备的功能以及已分配账号的角色禁止删除等不变量。
 */
public final class AdminRoleAggregate {
    private final String code;
    private final String name;
    private final String description;
    private final boolean builtIn;
    private final Set<String> functions;

    public AdminRoleAggregate(String code, String name, String description, boolean builtIn, Set<String> functions) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.builtIn = builtIn;
        this.functions = Set.copyOf(functions);
    }

    public String code() { return code; }
    public String name() { return name; }
    public String description() { return description; }
    public Set<String> functions() { return functions; }

    public static AdminRoleAggregate createCustom(
            String code,
            String name,
            String description,
            Collection<String> requestedFunctions,
            Set<String> knownFunctions,
            Set<String> operatorFunctions,
            Set<String> platformAdminOnlyFunctions
    ) {
        var validated = validateFunctions(requestedFunctions, knownFunctions, operatorFunctions, platformAdminOnlyFunctions);
        return new AdminRoleAggregate(code, name.strip(), cleanText(description), false, validated);
    }

    public AdminRoleAggregate updateCustom(
            String newName,
            String newDescription,
            Collection<String> requestedFunctions,
            Set<String> knownFunctions,
            Set<String> operatorFunctions,
            Set<String> platformAdminOnlyFunctions
    ) {
        if (builtIn) {
            throw DomainException.bad("内置角色不能修改");
        }
        var validated = validateFunctions(requestedFunctions, knownFunctions, operatorFunctions, platformAdminOnlyFunctions);
        return new AdminRoleAggregate(code, newName.strip(), cleanText(newDescription), false, validated);
    }

    public void assertDeletable(long assignedAccountCount) {
        if (builtIn) {
            throw DomainException.bad("内置角色不能删除");
        }
        if (assignedAccountCount > 0) {
            throw DomainException.conflict("仍有 " + assignedAccountCount + " 个管理员账号使用该角色，请先为这些账号更换角色");
        }
    }

    public String auditCreateDetail() {
        return "功能 " + String.join(", ", new TreeSet<>(functions));
    }

    public String auditUpdateDiffFrom(AdminRoleAggregate previous) {
        var added = new TreeSet<>(functions);
        added.removeAll(previous.functions);
        var removed = new TreeSet<>(previous.functions);
        removed.removeAll(functions);
        var parts = new ArrayList<String>();
        if (!added.isEmpty()) parts.add("新增功能 " + String.join(", ", added));
        if (!removed.isEmpty()) parts.add("移除功能 " + String.join(", ", removed));
        return String.join("；", parts);
    }

    private static Set<String> validateFunctions(
            Collection<String> requested,
            Set<String> knownFunctions,
            Set<String> operatorFunctions,
            Set<String> platformAdminOnlyFunctions
    ) {
        var validated = new TreeSet<String>();
        for (String code : requested) {
            if (code == null || !knownFunctions.contains(code)) {
                throw DomainException.bad("未知的管理功能: " + code);
            }
            if (platformAdminOnlyFunctions.contains(code)) {
                throw DomainException.forbidden("该功能仅属于超级管理员和平台管理员角色: " + code);
            }
            if (!operatorFunctions.contains(code)) {
                throw DomainException.forbidden("不能授予自己没有的管理功能: " + code);
            }
            validated.add(code);
        }
        return validated;
    }

    private static String cleanText(String value) {
        return Objects.requireNonNullElse(value, "").strip();
    }
}
