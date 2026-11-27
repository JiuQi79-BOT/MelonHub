package com.melonhub.rbac.service.ddd.domain.model;

import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.IsolationDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户身份与部门归属聚合根：封装部门级管理员归属权校验、用户资料/外部标识变更检测，
 * 以及移出部门时按隔离域精确识别待清除遗留角色分配的核心规则。
 */
public final class UserMembershipAggregate {
    public record DomainGrant(long departmentId, long applicationId, long roleId, String roleCode) {}

    public record StaleDomainRevocation(IsolationDomain domain, List<Long> roleIds, List<String> roleCodes) {}

    public record UpdatePlan(
            String normalizedEmail,
            boolean externalIdChanged,
            Set<Long> finalDepartments,
            List<Long> removedDepartments,
            List<StaleDomainRevocation> staleRevocations
    ) {
        public String auditDetail(String previousExternalId) {
            var parts = new ArrayList<String>();
            if (externalIdChanged) {
                parts.add("外部标识由 " + previousExternalId + " 变更，原有上下文已失效");
            }
            if (!removedDepartments.isEmpty()) {
                parts.add("移出部门 " + removedDepartments.size() + " 个，相关角色已解除");
            }
            return String.join("；", parts);
        }
    }

    private final long id;
    private final String name;
    private final String externalId;
    private final String email;
    private final List<Long> currentDepartments;

    public UserMembershipAggregate(long id, String name, String externalId, String email, List<Long> currentDepartments) {
        this.id = id;
        this.name = name;
        this.externalId = externalId;
        this.email = email;
        this.currentDepartments = List.copyOf(currentDepartments);
    }

    public long id() { return id; }
    public String externalId() { return externalId; }

    public static void assertCreateDepartmentsInScope(Set<Long> requestedDepartments, Collection<Long> scope) {
        if (scope != null && (requestedDepartments.isEmpty() || !scope.containsAll(requestedDepartments))) {
            throw DomainException.forbidden("只能在自己管理的部门中创建用户");
        }
    }

    public void assertFullyOwnedBy(Collection<Long> scope) {
        if (scope == null) return;
        if (currentDepartments.isEmpty() || !scope.containsAll(currentDepartments)) {
            throw DomainException.forbidden("只能操作完全属于你所管部门的用户");
        }
    }

    public UpdatePlan planUpdate(
            String newName,
            String newExternalId,
            String newEmail,
            Collection<Long> requestedDepartments,
            Collection<Long> operatorScope,
            List<DomainGrant> assignmentsInRemovedDepartments
    ) {
        String cleanEmail = Objects.requireNonNullElse(newEmail, "").strip();
        var wantedDepartments = new LinkedHashSet<>(requestedDepartments);
        if (operatorScope != null) {
            if (currentDepartments.stream().noneMatch(operatorScope::contains)) {
                throw DomainException.forbidden("该用户不在你管理的部门中");
            }
            var outsideCurrent = currentDepartments.stream()
                    .filter(d -> !operatorScope.contains(d))
                    .collect(Collectors.toSet());
            var outsideWanted = wantedDepartments.stream()
                    .filter(d -> !operatorScope.contains(d))
                    .collect(Collectors.toSet());
            if (!outsideCurrent.equals(outsideWanted)) {
                throw DomainException.forbidden("不能调整自己管理范围以外的部门成员关系");
            }
            boolean profileChanged = !externalId.equals(newExternalId)
                    || !newName.equals(name)
                    || !cleanEmail.equals(Objects.requireNonNullElse(email, ""));
            if (!outsideCurrent.isEmpty() && profileChanged) {
                throw DomainException.forbidden("该用户还属于你管理范围以外的部门，只能调整所管部门内的成员关系");
            }
        }
        boolean externalIdChanged = !externalId.equals(newExternalId);
        var removedDepartments = currentDepartments.stream()
                .filter(d -> !wantedDepartments.contains(d))
                .toList();
        var revocations = groupStaleRevocations(assignmentsInRemovedDepartments);
        return new UpdatePlan(cleanEmail, externalIdChanged, wantedDepartments, removedDepartments, revocations);
    }

    private static List<StaleDomainRevocation> groupStaleRevocations(List<DomainGrant> grants) {
        var grouped = new LinkedHashMap<IsolationDomain, List<DomainGrant>>();
        for (var grant : grants) {
            var domain = new IsolationDomain(grant.departmentId(), grant.applicationId());
            grouped.computeIfAbsent(domain, k -> new ArrayList<>()).add(grant);
        }
        var result = new ArrayList<StaleDomainRevocation>();
        for (var entry : grouped.entrySet()) {
            var roleIds = entry.getValue().stream().map(DomainGrant::roleId).toList();
            var roleCodes = entry.getValue().stream().map(DomainGrant::roleCode).toList();
            result.add(new StaleDomainRevocation(entry.getKey(), roleIds, roleCodes));
        }
        return result;
    }
}
