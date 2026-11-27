package com.melonhub.rbac.service.ddd.domain.model;

import com.melonhub.rbac.service.ddd.domain.value.CoursePermissionCatalog;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 统一身份账号聚合根：封装课程管理系统 9 个统一身份接口的领域规则（目标系统校验、Bearer 令牌解析、
 * 组织/用户/审计权限门禁、API operationId 权限判定以及已删除操作人回退投影）。
 */
public final class IdentityAccountAggregate {
    public record IdentityUser(long id, String externalId, String name) {}

    public record IdentityCredentialSnapshot(long userId, String username, String passwordHash) {}

    public record CourseAuditRecord(long id, Instant at, String type, String target, String actorNo, Long actorId, String actorName) {}

    public record Profile(String subject, List<String> roles, List<String> permissions, String name, String no) {}

    public record CourseAuditActor(Long id, String name, List<String> roles, boolean deleted) {}

    public record CourseAuditProjection(long id, String at, CourseAuditActor actor, String type, String target) {}

    private final IdentityUser user;
    private final List<String> rawRoles;
    private final List<String> permissions;

    public IdentityAccountAggregate(IdentityUser user, List<String> rawRoles, List<String> permissions) {
        this.user = user;
        this.rawRoles = List.copyOf(rawRoles);
        this.permissions = List.copyOf(permissions);
    }

    public static void validateLoginSystems(List<String> systems) {
        if (systems == null || systems.isEmpty() || !systems.contains(CoursePermissionCatalog.COURSE_SYSTEM)) {
            throw DomainException.bad("systems 必须包含 course-project-management");
        }
    }

    public static String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw DomainException.unauthorized("UNAUTHORIZED", "请先登录。");
        }
        return authorization.substring(7).trim();
    }

    public static void requireCourseAuditDepartments(List<Long> allowedDepartments) {
        if (allowedDepartments == null || allowedDepartments.isEmpty()) {
            throw DomainException.forbidden("你没有查看课程日志的权限。");
        }
    }

    public Profile profile() {
        return new Profile(user.externalId(), CoursePermissionCatalog.normalizeRoles(rawRoles), permissions, user.name(), user.externalId());
    }

    public boolean hasUiPermission(String permission) {
        return permissions.contains(permission);
    }

    public void requireOrganizationReadAccess() {
        if (!CoursePermissionCatalog.canReadOrganizations(rawRoles, permissions)) {
            throw DomainException.forbidden("你没有查询统一身份组织架构的权限。");
        }
    }

    public void requireUserLookupAccess() {
        if (!CoursePermissionCatalog.canLookupUsers(rawRoles, permissions)) {
            throw DomainException.forbidden("你没有查询统一身份组织架构的权限。");
        }
    }

    public static CourseAuditProjection projectCourseAuditEvent(CourseAuditRecord event, Map<Long, List<String>> actorRolesByUserId) {
        var actor = new CourseAuditActor(
                event.actorId(),
                event.actorId() == null ? event.actorNo() : event.actorName(),
                event.actorId() == null ? List.of() : actorRolesByUserId.getOrDefault(event.actorId(), List.of()),
                event.actorId() == null);
        return new CourseAuditProjection(event.id(), event.at().toString(), actor, event.type(), event.target());
    }
}
