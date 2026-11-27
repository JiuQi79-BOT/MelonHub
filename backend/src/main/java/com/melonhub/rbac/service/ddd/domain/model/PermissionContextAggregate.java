package com.melonhub.rbac.service.ddd.domain.model;

import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 权限上下文聚合根：封装全局单活部门切换（含 {@code switchDepartment=false} 时的 {@code DEPARTMENT_MISMATCH} 保护）、
 * 激活角色子集校验、最早到期刷新时刻 {@code refreshAt} 计算与批量权限判定。
 */
public final class PermissionContextAggregate {
    public record ContextSubject(
            long userId,
            boolean userEnabled,
            Long activeDepartmentId,
            long contextVersion,
            boolean applicationEnabled,
            boolean memberOfDepartment,
            List<Long> assignedRoleIds
    ) {}

    public record IssuancePlan(
            long userId,
            long departmentId,
            long applicationId,
            long contextVersion,
            boolean switchedDepartment,
            List<Long> activeRoleIds,
            Instant expiresAt
    ) {}

    public record EffectiveRole(long id, String name, String code, Instant expiresAt) {}

    public record EffectiveSnapshot(
            long userId,
            long departmentId,
            long applicationId,
            long contextVersion,
            Instant expiresAt,
            List<EffectiveRole> roles,
            List<String> permissions,
            Instant refreshAt
    ) {
        public CheckEvaluation evaluate(List<String> requestedPermissions) {
            var held = new HashSet<>(permissions);
            var decisions = new LinkedHashMap<String, Boolean>();
            boolean allowed = true;
            for (String code : requestedPermissions) {
                boolean ok = held.contains(code);
                decisions.put(code, ok);
                if (!ok) allowed = false;
            }
            return new CheckEvaluation(allowed, decisions, contextVersion);
        }
    }

    public record CheckEvaluation(boolean allowed, Map<String, Boolean> decisions, long contextVersion) {}

    private PermissionContextAggregate() {}

    public static IssuancePlan planIssuance(
            long applicationId,
            long targetDepartmentId,
            List<Long> requestedActiveRoleIds,
            Boolean switchDepartment,
            ContextSubject subject,
            Instant now,
            long ttlSeconds
    ) {
        if (!subject.userEnabled()) {
            throw DomainException.forbidden("用户已停用");
        }
        if (!subject.applicationEnabled()) {
            throw DomainException.forbidden("软件已停用");
        }
        if (!subject.memberOfDepartment()) {
            throw DomainException.forbidden("用户不属于目标部门");
        }
        var assigned = new LinkedHashSet<>(subject.assignedRoleIds());
        List<Long> activeRoles;
        if (requestedActiveRoleIds == null) {
            activeRoles = List.copyOf(assigned);
        } else {
            var deduped = new LinkedHashSet<>(requestedActiveRoleIds);
            if (!assigned.containsAll(deduped)) {
                throw DomainException.forbidden("激活角色必须为用户在当前隔离域内已分配的角色");
            }
            activeRoles = List.copyOf(deduped);
        }
        boolean switched = subject.activeDepartmentId() == null || subject.activeDepartmentId() != targetDepartmentId;
        if (switched && Boolean.FALSE.equals(switchDepartment) && subject.activeDepartmentId() != null) {
            throw DomainException.conflict("DEPARTMENT_MISMATCH",
                    "用户当前部门为 #" + subject.activeDepartmentId() + "，在部门 #" + targetDepartmentId + " 创建上下文需要显式切换部门");
        }
        long version = switched ? subject.contextVersion() + 1 : subject.contextVersion();
        Instant expiresAt = now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(ttlSeconds);
        return new IssuancePlan(subject.userId(), targetDepartmentId, applicationId, version, switched, activeRoles, expiresAt);
    }

    public static Instant earliestRefreshAt(Instant contextExpiresAt, Collection<Instant> roleExpiries) {
        Instant earliest = contextExpiresAt;
        for (Instant exp : roleExpiries) {
            if (exp != null && (earliest == null || exp.isBefore(earliest))) {
                earliest = exp;
            }
        }
        return earliest;
    }
}
