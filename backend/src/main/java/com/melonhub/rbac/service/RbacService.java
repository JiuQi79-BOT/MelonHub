package com.melonhub.rbac.service;

import com.melonhub.rbac.dao.RbacDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.Credentials;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RbacService {
    private final RbacDao dao;
    public RbacService(RbacDao dao) { this.dao = dao; }
    public Map<String,Object> health() { return Map.of("status", "UP", "database", dao.healthy() ? "UP" : "DOWN", "model", "RBAC0"); }
    public void audit(String actor, String action, String target, Long department, Long app) { dao.audit(actor, action, target, department, app); }
    public void domain(long dept, long app) { if (!dao.departmentExists(dept) || !dao.applicationExists(app)) throw ApiException.missing(); }
    public void scoped(String table, long id, long dept, long app) { if (!dao.scopedExists(table, id, dept, app)) throw ApiException.forbidden("关联记录不属于当前隔离域"); }
    public List<Map<String,Object>> departments() { return dao.departments(); }
    public List<Map<String,Object>> applications() { return dao.applications(); }
    public List<Map<String,Object>> departmentUsers(long dept) { return dao.departmentUsers(dept); }
    public List<Map<String,Object>> users() {
        var users = dao.users();
        var memberships = dao.memberships();
        for (var user : users) user.put("departmentIds", memberships.stream().filter(m -> ((Number)m.get("user_id")).longValue() == ((Number)user.get("id")).longValue()).map(m -> m.get("department_id")).toList());
        return users;
    }
    public List<Map<String,Object>> roles(long dept, long app) {
        domain(dept, app);
        var roles = dao.roles(dept, app);
        var relationships = dao.rolePermissions(dept, app);
        for (var role : roles) role.put("permissionIds", relationships.stream().filter(p -> ((Number)p.get("role_id")).longValue() == ((Number)role.get("id")).longValue()).map(p -> p.get("permission_id")).toList());
        return roles;
    }
    public List<Map<String,Object>> permissions(long dept, long app) { domain(dept, app); return dao.permissions(dept, app); }
    public Map<String,Object> assignments(long user, long dept, long app) { domain(dept, app); return Map.of("roleIds", dao.assignedRoles(user, dept, app)); }
    @Transactional public long department(Requests.Department body, Long id, String actor) {
        if (id == null) id = dao.createDepartment(body.name(), body.code());
        else if (dao.updateDepartment(id, body.name(), body.code()) == 0) throw ApiException.missing();
        audit(actor, "DEPARTMENT_SAVED", body.name(), id, null);
        return id;
    }
    @Transactional public Map<String,Object> application(Requests.Application body, String actor) {
        String key = "mh_" + Credentials.token();
        long id = dao.createApplication(body.name(), body.code(), Objects.requireNonNullElse(body.description(), ""), Credentials.hash(key));
        audit(actor, "APPLICATION_CREATED", body.name(), null, id);
        return Map.of("id", id, "apiKey", key);
    }
    @Transactional public String rotateKey(long id, String actor) {
        String key = "mh_" + Credentials.token();
        if (dao.rotateKey(id, Credentials.hash(key)) == 0) throw ApiException.missing();
        audit(actor, "API_KEY_ROTATED", String.valueOf(id), null, id);
        return key;
    }
    @Transactional public long updateApplication(long id, Requests.Application body, String actor) {
        if (dao.updateApplication(id, body.name(), body.code(), Objects.requireNonNullElse(body.description(), "")) == 0) throw ApiException.missing();
        audit(actor, "APPLICATION_SAVED", body.name(), null, id);
        return id;
    }
    @Transactional public long user(Requests.User body, Long id, String actor) {
        for (long dept : new HashSet<>(body.departmentIds())) if (!dao.departmentExists(dept)) throw ApiException.missing();
        if (id == null) id = dao.createUser(body.name(), body.externalId(), Objects.requireNonNullElse(body.email(), ""));
        else {
            if (!dao.lockUser(id)) throw ApiException.missing();
            dao.updateUser(id, body.name(), body.externalId(), Objects.requireNonNullElse(body.email(), ""));
        }
        for (long dept : dao.userDepartments(id)) if (!body.departmentIds().contains(dept)) dao.removeMembership(id, dept);
        for (long dept : new HashSet<>(body.departmentIds())) dao.addMembership(id, dept);
        dao.clearInactiveDepartment(id);
        audit(actor, "USER_SAVED", body.name(), null, null);
        return id;
    }
    @Transactional public long role(Requests.Role body, Long id, long dept, long app, String actor) {
        domain(dept, app);
        if (id == null) id = dao.createRole(dept, app, body.name(), body.code(), Objects.requireNonNullElse(body.description(), ""));
        else { scoped("roles", id, dept, app); dao.updateRole(id, body.name(), body.code(), Objects.requireNonNullElse(body.description(), "")); }
        audit(actor, "ROLE_SAVED", body.name(), dept, app);
        return id;
    }
    @Transactional public long permission(Requests.Permission body, Long id, long dept, long app, String actor) {
        domain(dept, app);
        if (id == null) id = dao.createPermission(dept, app, body.name(), body.code(), body.resource(), body.action());
        else { scoped("permissions", id, dept, app); dao.updatePermission(id, body.name(), body.code(), body.resource(), body.action()); }
        audit(actor, "PERMISSION_SAVED", body.name(), dept, app);
        return id;
    }
    @Transactional public void rolePermissions(long role, long dept, long app, List<Long> permissions, String actor) {
        scoped("roles", role, dept, app);
        for (long permission : new HashSet<>(permissions)) scoped("permissions", permission, dept, app);
        dao.clearRolePermissions(role);
        for (long permission : new HashSet<>(permissions)) dao.addRolePermission(role, permission, dept, app);
        audit(actor, "ROLE_PERMISSIONS_UPDATED", String.valueOf(role), dept, app);
    }
    @Transactional public void userRoles(long user, long dept, long app, List<Long> roles, String actor) {
        domain(dept, app);
        if (!dao.lockUser(user)) throw ApiException.missing();
        if (!dao.member(user, dept)) throw ApiException.forbidden("用户不属于当前部门");
        for (long role : new HashSet<>(roles)) scoped("roles", role, dept, app);
        dao.clearUserRoles(user, dept, app);
        for (long role : new HashSet<>(roles)) dao.addUserRole(user, role, dept, app);
        dao.invalidateUser(user);
        audit(actor, "USER_ROLES_UPDATED", String.valueOf(user), dept, app);
    }
    @Transactional public void enabled(String table, long id, boolean enabled, String actor) {
        if (dao.enabled(table, id, enabled) == 0) throw ApiException.missing();
        if (table.equals("users")) dao.invalidateUser(id);
        if (table.equals("applications") && !enabled) dao.clearApplicationContexts(id);
        audit(actor, enabled ? "RECORD_ENABLED" : "RECORD_DISABLED", table + ":" + id, null, null);
    }
    @Transactional public void delete(String table, long id, String actor) {
        if (dao.delete(table, id) == 0) throw ApiException.missing();
        audit(actor, "RECORD_DELETED", table + ":" + id, null, null);
    }
    public List<Map<String,Object>> events(Long dept, Long app) { return dao.events(dept, app); }
    public Map<String,Object> overview(long dept, long app) { domain(dept, app); return dao.overview(dept, app); }
}
