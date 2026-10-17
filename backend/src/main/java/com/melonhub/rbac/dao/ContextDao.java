package com.melonhub.rbac.dao;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContextDao {
    private final JdbcTemplate db;
    public ContextDao(JdbcTemplate db) { this.db = db; }
    public List<Map<String,Object>> lockUserByExternalId(String external) { return db.queryForList("SELECT id,enabled,active_department_id,context_version FROM users WHERE external_id=? FOR UPDATE", external); }
    public boolean applicationEnabled(long app) { return db.queryForObject("SELECT COUNT(*) FROM applications WHERE id=? AND enabled=TRUE", Long.class, app) > 0; }
    public boolean member(long user, Object dept) { return db.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id=? AND department_id=?", Long.class, user, dept) > 0; }
    public List<Long> assignedRoles(long user, long dept, long app) { return db.queryForList("SELECT r.id FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.department_id=? AND r.application_id=? AND r.enabled=TRUE", Long.class, user, dept, app); }
    public void switchDepartment(long user, long dept, long version) { db.update("UPDATE users SET active_department_id=?,context_version=? WHERE id=?", dept, version, user); }
    public void clearStaleContexts(long user, long version) { db.update("DELETE FROM permission_contexts WHERE user_id=? AND (expires_at<CURRENT_TIMESTAMP OR context_version<>?)", user, version); }
    public void create(String hash, long user, long dept, long app, long version, Instant expires) { db.update("INSERT INTO permission_contexts(id,user_id,department_id,application_id,context_version,expires_at) VALUES (?,?,?,?,?,?)", hash, user, dept, app, version, Timestamp.from(expires)); }
    public void activateRole(String hash, long role) { db.update("INSERT INTO context_roles VALUES (?,?)", hash, role); }
    public List<Map<String,Object>> find(String hash, long app) { return db.queryForList("SELECT * FROM permission_contexts WHERE id=? AND application_id=?", hash, app); }
    public List<Map<String,Object>> lockUser(long user) { return db.queryForList("SELECT context_version,active_department_id,enabled FROM users WHERE id=? FOR UPDATE", user); }
    public List<Map<String,Object>> effectiveRoles(long user, String hash, Object dept, long app) { return db.queryForList("SELECT r.id,r.name,r.code FROM roles r JOIN context_roles cr ON cr.role_id=r.id JOIN user_roles ur ON ur.role_id=r.id AND ur.user_id=? WHERE cr.context_id=? AND r.department_id=? AND r.application_id=? AND r.enabled=TRUE ORDER BY r.id", user, hash, dept, app); }
    public List<String> effectivePermissions(long user, String hash, Object dept, long app) { return db.queryForList("SELECT DISTINCT p.code FROM permissions p JOIN role_permissions rp ON rp.permission_id=p.id JOIN roles r ON r.id=rp.role_id JOIN context_roles cr ON cr.role_id=r.id JOIN user_roles ur ON ur.role_id=r.id AND ur.user_id=? WHERE cr.context_id=? AND r.enabled=TRUE AND p.department_id=? AND p.application_id=? ORDER BY p.code", String.class, user, hash, dept, app); }
    public void revoke(String hash, long app) { db.update("DELETE FROM permission_contexts WHERE id=? AND application_id=?", hash, app); }
}
