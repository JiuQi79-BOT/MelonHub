package com.melonhub.rbac.service;

import com.melonhub.rbac.cache.PermissionCache;
import com.melonhub.rbac.dao.ContextDao;
import com.melonhub.rbac.dao.GovernanceDao;
import com.melonhub.rbac.dao.RbacDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access governance: employee access requests with approval, periodic access reviews, time-bound assignments,
 * bulk user import, permission manifests and reverse lookup. Approvals and revocations invalidate contexts and
 * notify webhooks exactly like direct console changes.
 */
@Service
public class GovernanceService {
    private final GovernanceDao gov;
    private final RbacDao dao;
    private final ContextDao contexts;
    private final PermissionCache cache;
    private final RbacService rbac;
    private final WebhookService webhooks;
    private final Validator validator;
    public static final int HOLDER_LIMIT = 500;

    public GovernanceService(GovernanceDao gov, RbacDao dao, ContextDao contexts, PermissionCache cache, RbacService rbac, WebhookService webhooks,
                             Validator validator) {
        this.gov = gov; this.dao = dao; this.contexts = contexts; this.cache = cache; this.rbac = rbac; this.webhooks = webhooks;
        this.validator = validator;
    }

    // ---- access requests ----
    public Map<String,Object> requests(Long app, Collection<Long> visible, String status, String keyword, int page, int size) {
        if (status != null && !status.isBlank() && !List.of("PENDING", "APPROVED", "REJECTED", "CANCELLED").contains(status.strip()))
            throw ApiException.bad("状态只能是 PENDING、APPROVED、REJECTED 或 CANCELLED");
        return RbacService.page(gov.requests(app, visible, status, keyword, page, size), page, size);
    }
    public Map<String,Object> request(long id) { return gov.request(id).orElseThrow(() -> ApiException.missing("申请不存在")); }

    /** Business systems submit requests on behalf of an employee; approval happens in the console. */
    @Transactional public Map<String,Object> submit(long app, Requests.AccessRequestSubmit body, String actor) {
        long user = dao.lockUserByExternal(body.externalId()).orElseThrow(() -> ApiException.missing("用户不存在"));
        if (!dao.scopedExists("roles", body.roleId(), body.departmentId(), app)) throw ApiException.missing("角色不属于当前软件");
        if (gov.pendingExists(user, body.roleId())) throw ApiException.conflict("该用户已有此角色的待审批申请");
        long id;
        try {
            id = gov.createRequest(user, body.departmentId(), app, body.roleId(), body.reason().strip(), body.durationDays(), actor);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该用户已有此角色的待审批申请");
        }
        rbac.audit(actor, "ACCESS_REQUESTED", body.externalId(), body.departmentId(), app, "角色 #" + body.roleId());
        return request(id);
    }

    @Transactional public Map<String,Object> approve(long id, Requests.RequestApprove body, String actor) {
        var request = request(id);
        if (!"PENDING".equals(request.get("status"))) throw ApiException.conflict("申请已被处理");
        if (request.get("userId") == null || request.get("roleId") == null)
            throw ApiException.conflict("申请涉及的用户或角色已删除");
        long user = number(request.get("userId")), dept = number(request.get("departmentId"));
        long app = number(request.get("applicationId")), role = number(request.get("roleId"));
        if (!Boolean.TRUE.equals(request.get("roleEnabled"))) throw ApiException.conflict("角色已停用，无法授权");
        boolean permanent = Boolean.TRUE.equals(body.permanent());
        if (permanent && body.durationDays() != null) throw ApiException.bad("永久授权时不能同时指定天数");
        Integer days = permanent ? null : body.durationDays() != null ? body.durationDays()
                : request.get("durationDays") == null ? null : ((Number) request.get("durationDays")).intValue();
        Instant expires = days == null ? null : Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(days, ChronoUnit.DAYS);
        dao.lockUser(user).orElseThrow(() -> ApiException.missing("用户不存在"));
        dao.grantRole(user, dept, app, role, expires == null ? null : Timestamp.from(expires), actor);
        if (gov.decide(id, "APPROVED", actor, text(body.comment()), expires == null ? null : Timestamp.from(expires)) == 0)
            throw ApiException.conflict("申请已被并发处理");
        contexts.revokeDomain(user, dept, app);
        cache.touchUser(user);
        webhooks.emit(app, "access.approved", Map.of("userId", user, "externalId", request.get("externalId"), "departmentId", dept, "roleId", role));
        rbac.audit(actor, "ACCESS_REQUEST_APPROVED", (String) request.get("externalId"), dept, app,
                "角色 " + request.get("roleCode") + (days == null ? "，永久授权" : "，" + days + " 天有效"));
        return request(id);
    }

    @Transactional public Map<String,Object> reject(long id, Requests.RequestReject body, String actor) {
        var request = request(id);
        if (!"PENDING".equals(request.get("status"))) throw ApiException.conflict("申请已被处理");
        if (gov.decide(id, "REJECTED", actor, text(body.comment()), null) == 0) throw ApiException.conflict("申请已被并发处理");
        long app = number(request.get("applicationId"));
        var data = new LinkedHashMap<String,Object>();
        data.put("userId", request.get("userId"));
        data.put("externalId", request.get("externalId"));
        data.put("departmentId", request.get("departmentId"));
        data.put("roleId", request.get("roleId"));
        data.put("comment", text(body.comment()));
        webhooks.emit(app, "access.rejected", data);
        rbac.audit(actor, "ACCESS_REQUEST_REJECTED", (String) request.get("externalId"), (Long) request.get("departmentId"), app,
                "角色 " + request.get("roleCode") + (body.comment() == null || body.comment().isBlank() ? "" : "，理由：" + body.comment()));
        return request(id);
    }

    @Transactional public void cancelOwn(long app, long id, String externalId, String actor) {
        long user = dao.userIdByExternal(externalId).orElseThrow(() -> ApiException.missing("用户不存在"));
        var request = request(id);
        if (number(request.get("applicationId")) != app || !Long.valueOf(user).equals(request.get("userId"))) throw ApiException.missing("申请不存在");
        if (gov.cancelOwn(id, user) == 0) throw ApiException.conflict("申请已被处理，无法撤回");
        rbac.audit(actor, "ACCESS_REQUEST_CANCELLED", externalId, (Long) request.get("departmentId"), app, "角色 " + request.get("roleCode"));
    }

    /** The employee's own requests within the calling application, newest first. */
    public List<Map<String,Object>> myRequests(long app, String externalId) {
        long user = dao.userIdByExternal(externalId).orElseThrow(() -> ApiException.missing("用户不存在"));
        return gov.requestsOfUser(user, app);
    }

    /** Read-only view of what a user currently holds in one domain, without creating a context. */
    public Map<String,Object> myAccess(long app, String externalId, long dept) {
        long user = dao.userIdByExternal(externalId).orElseThrow(() -> ApiException.missing("用户不存在"));
        rbac.domain(dept, app);
        var roles = dao.userRolesInDomain(user, dept, app);
        var usable = new ArrayList<Long>();
        var view = new ArrayList<Map<String,Object>>();
        for (var role : roles) {
            boolean expired = role.get("expiresAt") != null && ((Timestamp) role.get("expiresAt")).toInstant().isBefore(Instant.now());
            var item = new LinkedHashMap<String,Object>();
            item.put("id", number(role.get("id")));
            item.put("name", role.get("name"));
            item.put("code", role.get("code"));
            item.put("enabled", role.get("enabled"));
            item.put("expiresAt", role.get("expiresAt"));
            if (Boolean.TRUE.equals(role.get("enabled")) && !expired) usable.add(number(role.get("id")));
            view.add(item);
        }
        var permissions = new LinkedHashSet<String>();
        for (var grant : dao.grants(usable)) permissions.add((String) grant.get("code"));
        var result = new LinkedHashMap<String,Object>();
        result.put("userId", user);
        result.put("departmentId", dept);
        result.put("applicationId", app);
        result.put("roles", view);
        result.put("permissions", new ArrayList<>(permissions));
        return result;
    }

    // ---- access reviews ----
    @Transactional public Map<String,Object> createReview(Requests.ReviewCreate body, String actor) {
        if (!dao.applicationExists(body.applicationId())) throw ApiException.missing("软件不存在");
        if (body.departmentId() != null && !dao.departmentExists(body.departmentId())) throw ApiException.missing("部门不存在");
        Timestamp due = body.dueDays() == null ? null : Timestamp.from(Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(body.dueDays(), ChronoUnit.DAYS));
        long id = gov.createReview(body.name().strip(), body.applicationId(), body.departmentId(), due, actor);
        int items = gov.insertSnapshot(id, body.applicationId(), body.departmentId());
        rbac.audit(actor, "REVIEW_CREATED", body.name(), body.departmentId(), body.applicationId(), "快照包含 " + items + " 条授权");
        return review(id);
    }
    public Map<String,Object> review(long id) { return gov.review(id).orElseThrow(() -> ApiException.missing("复核不存在")); }
    public List<Map<String,Object>> reviews(Long app, Collection<Long> visible, String status) {
        if (status != null && !status.isBlank() && !List.of("OPEN", "CLOSED").contains(status.strip())) throw ApiException.bad("状态只能是 OPEN 或 CLOSED");
        return gov.reviews(app, visible, status);
    }
    public Map<String,Object> reviewItems(long review, String decision, int page, int size) {
        review(review);
        if (decision != null && !decision.isBlank() && !List.of("PENDING", "KEEP", "REVOKE").contains(decision.strip()))
            throw ApiException.bad("决定只能是 PENDING、KEEP 或 REVOKE");
        return RbacService.page(gov.reviewItems(review, decision, page, size), page, size);
    }
    @Transactional public void decideReviewItem(long reviewId, long itemId, Requests.ReviewItemDecision body, String actor) {
        lockOpenReview(reviewId);
        if (gov.decideItem(itemId, reviewId, body.decision(), actor, text(body.comment())) == 0)
            throw ApiException.missing("复核条目不存在");
    }

    private void lockOpenReview(long reviewId) {
        String status = gov.lockReview(reviewId).orElseThrow(() -> ApiException.missing("复核不存在"));
        if (!"OPEN".equals(status)) throw ApiException.conflict("复核已关闭");
    }

    /**
     * Applies every REVOKE decision, then closes the review; undecided items are kept. Only the grant that was
     * snapshotted is revoked: an assignment removed, renewed or granted again after the snapshot is skipped.
     */
    @Transactional public Map<String,Object> closeReview(long reviewId, String actor) {
        lockOpenReview(reviewId);
        var review = review(reviewId);
        long app = number(review.get("applicationId"));
        var revocations = gov.revocations(reviewId);
        var users = new TreeSet<Long>();
        for (var item : revocations) if (item.get("userId") != null) users.add(number(item.get("userId")));
        dao.lockUsers(users);
        var domains = new LinkedHashMap<Long, List<Long>>();
        var touched = new LinkedHashSet<Long>();
        int revoked = 0, skipped = 0;
        for (var item : revocations) {
            boolean applied = item.get("userId") != null && item.get("roleId") != null
                    && gov.revokeGrant(number(item.get("userId")), number(item.get("roleId")), (String) item.get("grantId")) > 0;
            gov.markApplied(number(item.get("id")), !applied);
            if (!applied) { skipped++; continue; }
            long user = number(item.get("userId"));
            revoked++;
            touched.add(user);
            domains.computeIfAbsent(number(item.get("departmentId")), k -> new ArrayList<>()).add(user);
        }
        for (var entry : domains.entrySet()) contexts.revokeDomain(entry.getValue(), entry.getKey(), app);
        for (long user : touched) cache.touchUser(user);
        String summary = "回收 " + revoked + " 条授权" + (skipped == 0 ? "" : "，" + skipped + " 条在复核期间已变更或移除，未回收") + "，未决条目视为保留";
        if (gov.closeReview(reviewId, actor, summary) == 0) throw ApiException.conflict("复核已被并发关闭");
        if (revoked > 0) webhooks.emit(app, "review.closed", Map.of("reviewId", reviewId, "revoked", revoked));
        rbac.audit(actor, "REVIEW_CLOSED", (String) review.get("name"), (Long) review.get("departmentId"), app, summary);
        return review(reviewId);
    }

    // ---- assignment expiry ----
    /**
     * Deletes one batch of lapsed assignments, refreshes cached checks of the affected users and announces each
     * expiry once per user and domain, even when several instances run the job at the same time.
     */
    @Transactional public int expireAssignments(int limit) {
        var lapsed = new LinkedHashMap<String, List<Map<String,Object>>>();
        for (var row : dao.lapsedAssignments(limit)) {
            if (dao.deleteLapsedAssignment(number(row.get("userId")), number(row.get("roleId"))) == 0) continue;
            lapsed.computeIfAbsent(row.get("userId") + ":" + row.get("departmentId") + ":" + row.get("applicationId"), k -> new ArrayList<>()).add(row);
        }
        int expired = 0;
        for (var rows : lapsed.values()) {
            var first = rows.get(0);
            long user = number(first.get("userId")), dept = number(first.get("departmentId")), app = number(first.get("applicationId"));
            String external = (String) first.get("externalId");
            cache.touchUser(user);
            webhooks.emit(app, "user.roles.expired", Map.of("userId", user, "externalId", external, "departmentId", dept,
                    "roleIds", rows.stream().map(row -> number(row.get("roleId"))).toList()));
            rbac.audit("system", "USER_ROLES_EXPIRED", external, dept, app,
                    "到期角色 " + rows.stream().map(row -> (String) row.get("roleCode")).collect(Collectors.joining("、")));
            expired += rows.size();
        }
        return expired;
    }

    // ---- bulk user import ----
    /**
     * Creates or updates users by external id and adds department memberships; import never removes memberships.
     * Each row is validated on its own and a bad row is reported in failed without affecting the others. An
     * existing user's external id is never rewritten, so a concurrent console change of it is not undone.
     */
    @Transactional public Map<String,Object> importUsers(Requests.UserImport body, String actor) {
        var codes = new LinkedHashSet<String>();
        for (var row : body.users()) if (row != null && row.departmentCodes() != null) codes.addAll(row.departmentCodes());
        codes.removeIf(Objects::isNull);
        var departmentByCode = new HashMap<String,Long>();
        for (var row : dao.departmentIdsByCodes(codes)) departmentByCode.put((String) row.get("code"), number(row.get("id")));
        int created = 0, updated = 0;
        var failures = new ArrayList<Map<String,Object>>();
        int line = 0;
        for (var row : body.users()) {
            line++;
            try {
                invalid(row);
                var wanted = new LinkedHashSet<Long>();
                for (String code : row.departmentCodes()) {
                    Long dept = departmentByCode.get(code);
                    if (dept == null) throw ApiException.missing("部门编码不存在: " + code);
                    wanted.add(dept);
                }
                String email = row.email() == null ? "" : row.email().strip();
                var existing = dao.lockUserByExternal(row.externalId());
                long userId;
                if (existing.isPresent()) {
                    userId = existing.get();
                    dao.updateProfile(userId, row.name().strip(), email);
                    updated++;
                } else {
                    userId = dao.createUser(row.name().strip(), row.externalId(), email);
                    created++;
                }
                var current = new HashSet<>(dao.lockedUserDepartments(userId));
                for (long dept : wanted) if (!current.contains(dept)) dao.addMembership(userId, dept);
            } catch (ApiException | DataIntegrityViolationException e) {
                var failure = new LinkedHashMap<String,Object>();
                failure.put("line", line);
                failure.put("externalId", row == null ? null : row.externalId());
                failure.put("error", e instanceof ApiException ? e.getMessage() : "外部标识已存在或关联数据冲突");
                failures.add(failure);
            }
        }
        rbac.audit(actor, "USER_IMPORTED", "users", null, null, "新建 " + created + "，更新 " + updated + "，失败 " + failures.size());
        var result = new LinkedHashMap<String,Object>();
        result.put("created", created);
        result.put("updated", updated);
        result.put("failed", failures);
        return result;
    }

    private void invalid(Requests.UserImportRow row) {
        if (row == null) throw ApiException.bad("该行为空");
        var fields = new TreeSet<String>();
        for (var violation : validator.validate(row)) {
            var node = violation.getPropertyPath().iterator();
            fields.add(node.hasNext() ? node.next().getName() : "row");
        }
        if (!fields.isEmpty()) throw ApiException.bad("字段 " + String.join("、", fields) + " 缺失或格式不正确");
    }

    // ---- permission manifest ----
    @Transactional(readOnly=true) public Map<String,Object> exportManifest(long app, Long departmentFilter) {
        var application = dao.applicationRow(app).orElseThrow(() -> ApiException.missing("软件不存在"));
        var permissions = new ArrayList<Map<String,Object>>();
        var codesById = new HashMap<Long,String>();
        for (var p : dao.permissions(0, app)) {
            codesById.put(number(p.get("id")), (String) p.get("code"));
            permissions.add(Map.of("roleCode", p.get("roleCode"), "code", p.get("code"),
                    "name", p.get("name"), "resource", p.get("resource"), "action", p.get("action")));
        }
        var grantsByRole = new HashMap<Long,List<String>>();
        for (var pair : dao.rolePermissions(0, app))
            grantsByRole.computeIfAbsent(number(pair.get("role_id")), k -> new ArrayList<>())
                    .add(codesById.get(number(pair.get("permission_id"))));
        var roles = new ArrayList<Map<String,Object>>();
        for (var r : dao.roles(0, app)) {
            var item = new LinkedHashMap<String,Object>();
            item.put("code", r.get("code"));
            item.put("name", r.get("name"));
            item.put("description", r.get("description"));
            item.put("enabled", r.get("enabled"));
            item.put("permissions", grantsByRole.getOrDefault(number(r.get("id")), List.of()));
            roles.add(item);
        }
        return Map.of("version", 2, "application", Map.of("code", application.get("code"), "name", application.get("name")),
                "exportedAt", Instant.now().toString(), "permissions", permissions, "roles", roles);
    }

    /** Import matches roles within the application and permissions within their owning role. */
    @Transactional public Map<String,Object> importManifest(long app, Requests.Manifest manifest, String actor) {
        if (!dao.applicationExists(app)) throw ApiException.missing("软件不存在");
        if (!Integer.valueOf(2).equals(manifest.version())) throw ApiException.bad("请使用版本 2 的角色权限清单");
        var roleIds = new LinkedHashMap<String,Long>();
        var previous = new HashMap<Long,Boolean>();
        int rolesCreated = 0, rolesUpdated = 0, permissionsCreated = 0, permissionsUpdated = 0;
        for (var role : manifest.roles()) {
            if (roleIds.containsKey(role.code())) throw ApiException.bad("角色编码重复: " + role.code());
            Long id = dao.scopedIdByCode("roles", 0, app, role.code()).orElse(null);
            boolean enabled = role.enabled() == null || role.enabled();
            if (id == null) {
                id = dao.createRole(0, app, role.name(), role.code(), text(role.description()), enabled);
                rolesCreated++;
            } else {
                if (!dao.lockRole(id)) throw ApiException.missing("角色不存在");
                long existingId = id;
                var existing = dao.roles(0, app).stream().filter(r -> number(r.get("id")) == existingId).findFirst();
                previous.put(id, existing.isPresent() && Boolean.TRUE.equals(existing.get().get("enabled")));
                dao.updateRole(id, role.name(), role.code(), text(role.description()));
                dao.roleStatus(id, enabled);
                rolesUpdated++;
            }
            roleIds.put(role.code(), id);
        }
        var seen = new HashSet<String>();
        for (var permission : manifest.permissions()) {
            Long role = roleIds.get(permission.roleCode());
            if (role == null) throw ApiException.bad("权限所属角色不在清单中: " + permission.roleCode());
            if (!seen.add(role + ":" + permission.code())) throw ApiException.bad("同一角色内权限编码重复: " + permission.code());
            var existing = dao.permissionIdByCode(role, permission.code());
            if (existing.isPresent()) {
                dao.updatePermission(existing.get(), permission.name(), permission.code(), permission.resource(), permission.action());
                webhooks.emit(app, "permission.updated", Map.of("permissionId", existing.get(), "roleId", role));
                permissionsUpdated++;
            } else {
                dao.createPermission(role, 0, app, permission.name(), permission.code(), permission.resource(), permission.action());
                permissionsCreated++;
            }
        }
        for (var role : manifest.roles()) {
            long id = roleIds.get(role.code());
            var grants = new LinkedHashSet<Long>();
            for (String code : role.permissions())
                grants.add(dao.permissionIdByCode(id, code).orElseThrow(() -> ApiException.bad("角色 " + role.code() + " 没有权限 " + code)));
            var before = new HashSet<>(dao.lockedRolePermissionIds(id));
            dao.replaceRolePermissions(id, 0, app, grants);
            if (!before.equals(grants)) webhooks.emit(app, "role.permissions.changed", Map.of("roleId", id));
            boolean enabled = role.enabled() == null || role.enabled();
            if (previous.containsKey(id) && previous.get(id) != enabled)
                webhooks.emit(app, enabled ? "role.enabled" : "role.disabled", Map.of("roleId", id));
        }
        cache.touchApplication(app);
        rbac.audit(actor, "MANIFEST_IMPORTED", "application:" + app, null, app,
                "新建权限 " + permissionsCreated + "、更新权限 " + permissionsUpdated + "、新建角色 " + rolesCreated + "、更新角色 " + rolesUpdated);
        return Map.of("permissionsCreated", permissionsCreated, "permissionsUpdated", permissionsUpdated,
                "rolesCreated", rolesCreated, "rolesUpdated", rolesUpdated);
    }

    public Map<String,Object> permissionHolders(long app, String code, Long department) {
        return permissionHolders(app, code, department, null);
    }
    public Map<String,Object> permissionHolders(long app, String code, Long department, Long ownerRole) {
        if (!dao.applicationExists(app)) throw ApiException.missing("软件不存在");
        if (ownerRole != null && !dao.scopedExists("roles", ownerRole, 0, app)) throw ApiException.missing("角色不属于当前软件");
        var grouped = new LinkedHashMap<String,Map<String,Object>>();
        for (var row : dao.permissionHolders(app, code, department, ownerRole, HOLDER_LIMIT)) {
            var holder = grouped.computeIfAbsent(row.get("userId") + ":" + row.get("departmentId"), k -> {
                var h = new LinkedHashMap<String,Object>();
                h.put("userId", number(row.get("userId")));
                h.put("userName", row.get("userName"));
                h.put("externalId", row.get("externalId"));
                h.put("departmentId", number(row.get("departmentId")));
                h.put("departmentName", row.get("departmentName"));
                h.put("roles", new ArrayList<Map<String,Object>>());
                return h;
            });
            var role = new LinkedHashMap<String,Object>();
            role.put("id", number(row.get("roleId")));
            role.put("name", row.get("roleName"));
            role.put("code", row.get("roleCode"));
            role.put("expiresAt", row.get("expiresAt"));
            rows(holder.get("roles")).add(role);
        }
        var result = new LinkedHashMap<String,Object>();
        result.put("code", code);
        result.put("applicationId", app);
        long total = dao.permissionHolderCount(app, code, department, ownerRole);
        result.put("holders", new ArrayList<>(grouped.values()));
        result.put("total", total);
        result.put("truncated", total > grouped.size());
        return result;
    }
    public List<Map<String,Object>> expiringAssignments(int days, Collection<Long> visible, Long app) {
        return dao.expiringAssignments(Timestamp.from(Instant.now().plus(days, ChronoUnit.DAYS)), visible, app);
    }

    private static long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private static String text(String value) { return Objects.requireNonNullElse(value, "").strip(); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value) { return (List<Map<String,Object>>) value; }
}
