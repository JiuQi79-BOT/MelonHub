package com.melonhub.rbac.service.ddd.domain.model;

import com.melonhub.rbac.service.ddd.domain.value.AdminScope;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 管理员层级树聚合根：守护管理员树的全部不变量（无环、部门管理员为受限叶子、下级功能与范围不越上级、
 * 内置软件/部门管理员作用域轴约束、至少保留一名启用超管、禁止越权或自改关键属性）。
 */
public final class AdminHierarchy {
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String APP_ADMIN = "APP_ADMIN";
    public static final String DEPARTMENT_ADMIN = "DEPARTMENT_ADMIN";
    public static final String MANAGER = "MANAGER";
    public static final String DEPARTMENT = "DEPARTMENT";
    private static final Set<String> ADMINISTRATION_FUNCTIONS = Set.of("account.manage", "role.manage");

    public record AdminNode(
            long id,
            String username,
            Long parentId,
            String kind,
            String role,
            boolean enabled,
            AdminScope scope
    ) {
        public boolean isRoot() { return parentId == null; }
    }

    public record UpdateDecision(
            AdminNode target,
            AdminNode newParent,
            boolean roleChanged,
            boolean disabling,
            boolean kindChanged,
            boolean parentChanged,
            boolean revokeSessions
    ) {}

    private final Map<Long, AdminNode> nodes = new LinkedHashMap<>();
    private final Map<Long, List<Long>> children = new HashMap<>();
    private final Map<String, Set<String>> roleFunctions;

    public AdminHierarchy(Collection<AdminNode> accounts, Map<String, Set<String>> roleFunctions) {
        this.roleFunctions = roleFunctions;
        for (var node : accounts) {
            nodes.put(node.id(), node);
        }
        for (var node : accounts) {
            if (node.parentId() != null) {
                children.computeIfAbsent(node.parentId(), k -> new ArrayList<>()).add(node.id());
            }
        }
    }

    public Collection<AdminNode> accounts() { return nodes.values(); }

    public AdminNode node(long id) { return nodes.get(id); }

    public List<Long> children(long id) { return children.getOrDefault(id, List.of()); }

    public Set<Long> descendants(long id) {
        var found = new TreeSet<Long>();
        var pending = new ArrayDeque<>(children(id));
        while (!pending.isEmpty()) {
            long next = pending.poll();
            if (found.add(next)) pending.addAll(children(next));
        }
        return found;
    }

    public boolean manages(long actorId, long targetId) {
        var self = nodes.get(actorId);
        if (self == null || actorId == targetId || !nodes.containsKey(targetId)) return false;
        return self.isRoot() || descendants(actorId).contains(targetId);
    }

    public AdminNode requireManaged(long actorId, long targetId) {
        var target = nodes.get(targetId);
        if (target == null) throw DomainException.missing("管理员账号不存在");
        if (!manages(actorId, targetId)) throw DomainException.forbidden("只能管理自己下级的管理员账号");
        return target;
    }

    public long enabledSuperAdmins() {
        return nodes.values().stream()
                .filter(a -> a.enabled() && SUPER_ADMIN.equals(a.role()))
                .count();
    }

    public AdminNode validateCreate(long actorId, AdminNode candidate) {
        if (candidate.parentId() == null) throw DomainException.bad("上级管理员不存在");
        var parent = nodes.get(candidate.parentId());
        if (parent == null) throw DomainException.bad("上级管理员不存在");
        if (candidate.parentId() != actorId && !manages(actorId, candidate.parentId())) {
            throw DomainException.forbidden("只能在自己或自己管理的管理员下创建账号");
        }
        requireSoftwareRoleScope(candidate.role(), candidate.kind(), candidate.scope());
        requireConsistent(withCandidate(candidate), roleFunctions);
        return parent;
    }

    public UpdateDecision validateUpdate(long actorId, AdminNode proposed) {
        var current = nodes.get(proposed.id());
        if (current == null) throw DomainException.missing("管理员账号不存在");
        boolean self = proposed.id() == actorId;
        if (!self && !manages(actorId, proposed.id())) {
            throw DomainException.forbidden("只能管理自己下级的管理员账号");
        }
        boolean roleChanged = !current.role().equals(proposed.role());
        boolean disabling = current.enabled() && !proposed.enabled();
        boolean kindChanged = !current.kind().equals(proposed.kind());
        boolean parentChanged = !Objects.equals(current.parentId(), proposed.parentId());
        boolean scopeChanged = !current.scope().equals(proposed.scope());

        if (self && (roleChanged || disabling || kindChanged || parentChanged || scopeChanged)) {
            throw DomainException.bad("不能修改自己的角色、类型、管理范围或上级，也不能停用自己的账号");
        }
        if (roleChanged || kindChanged || scopeChanged) {
            requireSoftwareRoleScope(proposed.role(), proposed.kind(), proposed.scope());
        }
        if ((roleChanged || disabling) && current.enabled() && SUPER_ADMIN.equals(current.role()) && enabledSuperAdmins() <= 1) {
            throw DomainException.conflict("至少需要保留一个启用的超级管理员");
        }
        if (disabling && !children(proposed.id()).isEmpty()) {
            throw DomainException.conflict("该管理员还有 " + children(proposed.id()).size() + " 个下级管理员，请先移交或删除下级后再停用");
        }
        AdminNode newParent = null;
        if (parentChanged) {
            newParent = proposed.parentId() == null ? null : nodes.get(proposed.parentId());
            if (newParent == null) throw DomainException.bad("上级管理员不存在");
            if (proposed.parentId() != actorId && !manages(actorId, proposed.parentId())) {
                throw DomainException.forbidden("只能移到自己或自己管理的管理员之下");
            }
        }
        requireConsistent(withCandidate(proposed), roleFunctions);
        return new UpdateDecision(current, newParent, roleChanged, disabling, kindChanged, parentChanged,
                roleChanged || disabling || kindChanged);
    }

    public AdminNode validateDelete(long actorId, long targetId) {
        if (targetId == actorId) throw DomainException.bad("不能删除当前登录的账号");
        var target = requireManaged(actorId, targetId);
        if (!children(targetId).isEmpty()) {
            throw DomainException.conflict("该管理员还有 " + children(targetId).size() + " 个下级管理员，请先移交或删除下级");
        }
        if (target.enabled() && SUPER_ADMIN.equals(target.role()) && enabledSuperAdmins() <= 1) {
            throw DomainException.conflict("至少需要保留一个启用的超级管理员");
        }
        return target;
    }

    public void requireConsistentWithRoleChange(String roleCode, Set<String> updatedFunctions) {
        var nextRoles = new HashMap<>(roleFunctions);
        nextRoles.put(roleCode, updatedFunctions);
        requireConsistent(nodes.values(), nextRoles);
    }

    public List<String> violations() {
        var problems = new ArrayList<String>();
        for (var node : nodes.values()) {
            String name = node.username();
            var functions = roleFunctions.getOrDefault(node.role(), Set.of());
            if (DEPARTMENT_ADMIN.equals(node.role())
                    && (!DEPARTMENT.equals(node.kind()) || !node.scope().allApplications())) {
                problems.add(name + "：内置部门管理员角色按部门划定范围，并覆盖这些部门的全部软件");
            }
            if (DEPARTMENT.equals(node.kind())) {
                if (node.scope().allDepartments() || node.scope().departments().isEmpty()) {
                    problems.add(name + "：部门管理员必须指定所管理的部门");
                }
                if (functions.stream().anyMatch(ADMINISTRATION_FUNCTIONS::contains)) {
                    problems.add(name + "：部门管理员不能拥有管理管理员账号或角色的功能");
                }
                if (!children(node.id()).isEmpty()) {
                    problems.add(name + "：部门管理员不能有下级管理员");
                }
            } else if (!MANAGER.equals(node.kind())) {
                problems.add(name + "：未知的管理员类型 " + node.kind());
            }
            if (node.parentId() == null) continue;
            var parent = nodes.get(node.parentId());
            if (parent == null) {
                problems.add(name + "：上级管理员不存在");
                continue;
            }
            if (loops(node.id())) {
                problems.add(name + "：上级关系形成循环");
                continue;
            }
            var beyond = new TreeSet<>(functions);
            beyond.removeAll(roleFunctions.getOrDefault(parent.role(), Set.of()));
            if (!beyond.isEmpty()) {
                problems.add(name + "：角色功能超出上级 " + parent.username() + "（" + String.join(", ", beyond) + "）");
            }
            if (!parent.scope().coversDepartmentsOf(node.scope())) {
                problems.add(name + "：部门范围超出上级 " + parent.username());
            }
            if (!parent.scope().coversApplicationsOf(node.scope())) {
                problems.add(name + "：软件范围超出上级 " + parent.username());
            }
        }
        return problems;
    }

    public static void requireSoftwareRoleScope(String role, String kind, AdminScope scope) {
        if (APP_ADMIN.equals(role)
                && (!MANAGER.equals(kind) || !scope.allDepartments() || scope.allApplications() || scope.applications().isEmpty())) {
            throw DomainException.conflict("内置软件管理员角色需要指定可管理的软件，部门范围为全部部门");
        }
    }

    private Collection<AdminNode> withCandidate(AdminNode changed) {
        var result = new ArrayList<AdminNode>(nodes.size() + 1);
        for (var node : nodes.values()) {
            if (node.id() != changed.id()) result.add(node);
        }
        result.add(changed);
        return result;
    }

    private static void requireConsistent(Collection<AdminNode> candidateNodes, Map<String, Set<String>> candidateRoles) {
        var problems = new AdminHierarchy(candidateNodes, candidateRoles).violations();
        if (!problems.isEmpty()) {
            throw DomainException.conflict("管理员层级不满足要求："
                    + String.join("；", problems.subList(0, Math.min(3, problems.size()))));
        }
    }

    private boolean loops(long id) {
        var seen = new HashSet<Long>();
        for (Long current = id; current != null; current = nodes.containsKey(current) ? nodes.get(current).parentId() : null) {
            if (!seen.add(current)) return true;
        }
        return false;
    }
}
