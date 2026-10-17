package com.melonhub.rbac.dao;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class RbacDao {
    private final JdbcTemplate db;
    public RbacDao(JdbcTemplate db) { this.db = db; }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    public boolean healthy() { return db.queryForObject("SELECT 1", Integer.class) == 1; }
    public void audit(String actor, String action, String target, Long department, Long app) {
        db.update("INSERT INTO audit_events(actor,action,target,department_id,application_id) VALUES (?,?,?,?,?)", actor, action, target, department, app);
    }
    public boolean departmentExists(long id) { return db.queryForObject("SELECT COUNT(*) FROM departments WHERE id=?", Long.class, id) > 0; }
    public boolean applicationExists(long id) { return db.queryForObject("SELECT COUNT(*) FROM applications WHERE id=?", Long.class, id) > 0; }
    public boolean scopedExists(String table, long id, long dept, long app) {
        if (!Set.of("roles", "permissions").contains(table)) throw new IllegalArgumentException();
        return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=? AND department_id=? AND application_id=?", Long.class, id, dept, app) > 0;
    }
    public List<Map<String,Object>> departments() { return db.queryForList("SELECT d.id,d.name,d.code,d.created_at AS createdAt,(SELECT COUNT(*) FROM memberships m WHERE m.department_id=d.id) AS memberCount FROM departments d ORDER BY d.id"); }
    public List<Map<String,Object>> applications() { return db.queryForList("SELECT a.id,a.name,a.code,a.description,a.enabled,a.created_at AS createdAt,(SELECT COUNT(*) FROM roles r WHERE r.application_id=a.id) AS roleCount,(SELECT COUNT(*) FROM permissions p WHERE p.application_id=a.id) AS permissionCount FROM applications a ORDER BY a.id"); }
    public List<Map<String,Object>> users() { return db.queryForList("SELECT id,name,external_id AS externalId,email,enabled,active_department_id AS activeDepartmentId,context_version AS contextVersion FROM users ORDER BY id"); }
    public List<Map<String,Object>> memberships() { return db.queryForList("SELECT user_id,department_id FROM memberships"); }
    public List<Map<String,Object>> departmentUsers(long dept) { return db.queryForList("SELECT u.id,u.name,u.external_id AS externalId,u.enabled FROM users u JOIN memberships m ON m.user_id=u.id WHERE m.department_id=? ORDER BY u.id", dept); }
    public List<Map<String,Object>> roles(long dept, long app) { return db.queryForList("SELECT r.id,r.name,r.code,r.description,r.enabled,r.created_at AS createdAt,(SELECT COUNT(*) FROM user_roles ur WHERE ur.role_id=r.id) AS memberCount FROM roles r WHERE r.department_id=? AND r.application_id=? ORDER BY r.id", dept, app); }
    public List<Map<String,Object>> rolePermissions(long dept, long app) { return db.queryForList("SELECT role_id,permission_id FROM role_permissions WHERE department_id=? AND application_id=?", dept, app); }
    public List<Map<String,Object>> permissions(long dept, long app) { return db.queryForList("SELECT p.id,p.name,p.code,p.resource,p.action,p.created_at AS createdAt,(SELECT COUNT(*) FROM role_permissions rp WHERE rp.permission_id=p.id) AS roleCount FROM permissions p WHERE p.department_id=? AND p.application_id=? ORDER BY p.id", dept, app); }
    public List<Long> assignedRoles(long user, long dept, long app) { return db.queryForList("SELECT role_id FROM user_roles WHERE user_id=? AND department_id=? AND application_id=?", Long.class, user, dept, app); }
    public long createDepartment(String name, String code) { return insert("INSERT INTO departments(name,code) VALUES (?,?)", name, code); }
    public int updateDepartment(long id, String name, String code) { return db.update("UPDATE departments SET name=?,code=? WHERE id=?", name, code, id); }
    public long createApplication(String name, String code, String description, String keyHash) { return insert("INSERT INTO applications(name,code,description,api_key_hash) VALUES (?,?,?,?)", name, code, description, keyHash); }
    public int rotateKey(long id, String hash) { return db.update("UPDATE applications SET api_key_hash=? WHERE id=?", hash, id); }
    public int updateApplication(long id, String name, String code, String description) { return db.update("UPDATE applications SET name=?,code=?,description=? WHERE id=?", name, code, description, id); }
    public long createUser(String name, String external, String email) { return insert("INSERT INTO users(name,external_id,email) VALUES (?,?,?)", name, external, email); }
    public boolean lockUser(long id) { return !db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, id).isEmpty(); }
    public void updateUser(long id, String name, String external, String email) { db.update("UPDATE users SET name=?,external_id=?,email=?,context_version=context_version+1 WHERE id=?", name, external, email, id); }
    public List<Long> userDepartments(long user) { return db.queryForList("SELECT department_id FROM memberships WHERE user_id=?", Long.class, user); }
    public void removeMembership(long user, long dept) { db.update("DELETE FROM memberships WHERE user_id=? AND department_id=?", user, dept); }
    public void addMembership(long user, long dept) { db.update("INSERT IGNORE INTO memberships VALUES (?,?)", user, dept); }
    public void clearInactiveDepartment(long user) { db.update("UPDATE users SET active_department_id=NULL WHERE id=? AND active_department_id NOT IN (SELECT department_id FROM memberships WHERE user_id=?)", user, user); }
    public boolean member(long user, long dept) { return db.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id=? AND department_id=?", Long.class, user, dept) > 0; }
    public long createRole(long dept, long app, String name, String code, String description) { return insert("INSERT INTO roles(department_id,application_id,name,code,description) VALUES (?,?,?,?,?)", dept, app, name, code, description); }
    public void updateRole(long id, String name, String code, String description) { db.update("UPDATE roles SET name=?,code=?,description=? WHERE id=?", name, code, description, id); }
    public long createPermission(long dept, long app, String name, String code, String resource, String action) { return insert("INSERT INTO permissions(department_id,application_id,name,code,resource,action) VALUES (?,?,?,?,?,?)", dept, app, name, code, resource, action); }
    public void updatePermission(long id, String name, String code, String resource, String action) { db.update("UPDATE permissions SET name=?,code=?,resource=?,action=? WHERE id=?", name, code, resource, action, id); }
    public void clearRolePermissions(long role) { db.update("DELETE FROM role_permissions WHERE role_id=?", role); }
    public void addRolePermission(long role, long permission, long dept, long app) { db.update("INSERT INTO role_permissions VALUES (?,?,?,?)", role, permission, dept, app); }
    public void clearUserRoles(long user, long dept, long app) { db.update("DELETE FROM user_roles WHERE user_id=? AND department_id=? AND application_id=?", user, dept, app); }
    public void addUserRole(long user, long role, long dept, long app) { db.update("INSERT INTO user_roles VALUES (?,?,?,?)", user, role, dept, app); }
    public void invalidateUser(long user) { db.update("UPDATE users SET context_version=context_version+1 WHERE id=?", user); }
    public void clearApplicationContexts(long app) { db.update("DELETE FROM permission_contexts WHERE application_id=?", app); }
    public int enabled(String table, long id, boolean enabled) {
        if (!Set.of("users", "applications", "roles").contains(table)) throw new IllegalArgumentException();
        return db.update("UPDATE " + table + " SET enabled=? WHERE id=?", enabled, id);
    }
    public int delete(String table, long id) {
        if (!Set.of("departments", "applications", "users", "roles", "permissions").contains(table)) throw new IllegalArgumentException();
        return db.update("DELETE FROM " + table + " WHERE id=?", id);
    }
    public List<Map<String,Object>> events(Long dept, Long app) { return db.queryForList("SELECT e.id,e.actor,e.action,e.target,e.created_at AS createdAt,d.name AS departmentName,a.name AS applicationName FROM audit_events e LEFT JOIN departments d ON d.id=e.department_id LEFT JOIN applications a ON a.id=e.application_id WHERE (? IS NULL OR e.department_id=?) AND (? IS NULL OR e.application_id=?) ORDER BY e.id DESC LIMIT 100", dept, dept, app, app); }
    public Map<String,Object> overview(long dept, long app) {
        return Map.of("applications", db.queryForObject("SELECT COUNT(*) FROM applications", Long.class), "departments", db.queryForObject("SELECT COUNT(*) FROM departments", Long.class), "users", db.queryForObject("SELECT COUNT(*) FROM users", Long.class), "roles", db.queryForObject("SELECT COUNT(*) FROM roles WHERE department_id=? AND application_id=?", Long.class, dept, app), "permissions", db.queryForObject("SELECT COUNT(*) FROM permissions WHERE department_id=? AND application_id=?", Long.class, dept, app), "members", db.queryForObject("SELECT COUNT(DISTINCT user_id) FROM user_roles WHERE department_id=? AND application_id=?", Long.class, dept, app), "activeContexts", db.queryForObject("SELECT COUNT(*) FROM permission_contexts c JOIN users u ON u.id=c.user_id JOIN applications a ON a.id=c.application_id WHERE c.context_version=u.context_version AND c.department_id=u.active_department_id AND c.expires_at>CURRENT_TIMESTAMP AND u.enabled=TRUE AND a.enabled=TRUE", Long.class));
    }
}
