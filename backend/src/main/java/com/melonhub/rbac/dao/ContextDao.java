package com.melonhub.rbac.dao;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContextDao {
    public record ContextRow(long user, long department, long application, long version, Instant expiresAt) {}
    /** A stored context and whether its user, application, membership and version still allow it. */
    public record ContextState(ContextRow context, boolean valid) {}

    private final JdbcTemplate db;
    public ContextDao(JdbcTemplate db) { this.db = db; }

    public List<Map<String,Object>> lockUserByExternalId(String external) {
        return db.queryForList("SELECT id,enabled,active_department_id,context_version FROM users WHERE external_id=? FOR UPDATE", external);
    }
    public boolean applicationEnabled(long app) { return db.queryForObject("SELECT COUNT(*) FROM applications WHERE id=? AND enabled=TRUE", Long.class, app) > 0; }
    public boolean member(long user, long dept) { return db.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id=? AND department_id=?", Long.class, user, dept) > 0; }
    public List<Long> assignedRoles(long user, long dept, long app) {
        return db.queryForList("SELECT r.id FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.application_id=? "
                + "AND r.enabled=TRUE AND (ur.expires_at IS NULL OR ur.expires_at>CURRENT_TIMESTAMP) ORDER BY r.id",
                Long.class, user, app);
    }
    public void switchDepartment(long user, long dept, long version) { db.update("UPDATE users SET active_department_id=?,context_version=? WHERE id=?", dept, version, user); }
    public void clearStaleContexts(long user, long version) {
        db.update("DELETE FROM permission_contexts WHERE user_id=? AND (expires_at<CURRENT_TIMESTAMP OR context_version<>?)", user, version);
    }
    public void create(String hash, long user, long dept, long app, long version, Instant expires) {
        db.update("INSERT INTO permission_contexts(id,user_id,department_id,application_id,context_version,expires_at) VALUES (?,?,?,?,?,?)",
                hash, user, dept == 0 ? null : dept, app, version, Timestamp.from(expires));
    }
    public void activateRoles(String hash, Collection<Long> roles) {
        db.batchUpdate("INSERT INTO context_roles(context_id,role_id) VALUES (?,?)", roles.stream().map(role -> new Object[]{hash, role}).toList());
    }

    /** Lock-free validation; callers run it inside one transaction so every query reads the same snapshot. */
    public Optional<ContextState> state(String hash) {
        return db.query("SELECT c.user_id,c.department_id,c.application_id,c.context_version,c.expires_at,"
                + "COALESCE(u.enabled AND a.enabled AND u.context_version=c.context_version ,FALSE) "
                + "FROM permission_contexts c JOIN users u ON u.id=c.user_id JOIN applications a ON a.id=c.application_id WHERE c.id=?",
                (rs, i) -> new ContextState(new ContextRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getTimestamp(5).toInstant()), rs.getBoolean(6)),
                hash).stream().findFirst();
    }
    public List<Map<String,Object>> effectiveRoles(long user, String hash, long dept, long app) {
        return db.queryForList("SELECT r.id,r.name,r.code,ur.expires_at FROM roles r JOIN context_roles cr ON cr.role_id=r.id JOIN user_roles ur ON ur.role_id=r.id AND ur.user_id=? "
                + "AND (ur.expires_at IS NULL OR ur.expires_at>CURRENT_TIMESTAMP) "
                + "WHERE cr.context_id=? AND r.application_id=? AND r.enabled=TRUE ORDER BY r.id", user, hash, app);
    }
    public List<String> effectivePermissions(long user, String hash, long dept, long app) {
        return db.queryForList("SELECT DISTINCT p.code FROM permissions p JOIN role_permissions rp ON rp.permission_id=p.id JOIN roles r ON r.id=rp.role_id "
                + "JOIN context_roles cr ON cr.role_id=r.id JOIN user_roles ur ON ur.role_id=r.id AND ur.user_id=? "
                + "AND (ur.expires_at IS NULL OR ur.expires_at>CURRENT_TIMESTAMP) "
                + "WHERE cr.context_id=? AND r.enabled=TRUE AND p.application_id=? ORDER BY p.code", String.class, user, hash, app);
    }
    public int revoke(String hash, long app) { return db.update("DELETE FROM permission_contexts WHERE id=? AND application_id=?", hash, app); }
    public int revokeDomain(long user, long dept, long app) {
        return db.update("DELETE FROM permission_contexts WHERE user_id=? AND application_id=?", user, app);
    }
    public int revokeDomain(Collection<Long> users, long dept, long app) {
        if (users.isEmpty()) return 0;
        Object[] args = new Object[users.size() + 1];
        args[0] = app;
        int i = 1;
        for (long user : users) args[i++] = user;
        return db.update("DELETE FROM permission_contexts WHERE application_id=? AND user_id IN (" + AuthDao.placeholders(users.size()) + ")", args);
    }
    public int revokeUser(long user) { return db.update("DELETE FROM permission_contexts WHERE user_id=?", user); }
    public int clearApplication(long app) { return db.update("DELETE FROM permission_contexts WHERE application_id=?", app); }
    public int clearDepartment(long dept) { return db.update("DELETE FROM permission_contexts WHERE department_id=?", dept); }
    public List<Map<String,Object>> activeContexts(long user) {
        return db.queryForList("SELECT c.department_id AS departmentId,d.name AS departmentName,c.application_id AS applicationId,a.name AS applicationName,"
                + "c.created_at AS createdAt,c.expires_at AS expiresAt,(SELECT COUNT(*) FROM context_roles cr WHERE cr.context_id=c.id) AS activeRoleCount,"
                + "COALESCE(u.enabled AND a.enabled AND u.context_version=c.context_version ,FALSE) AS valid "
                + "FROM permission_contexts c JOIN users u ON u.id=c.user_id LEFT JOIN departments d ON d.id=c.department_id JOIN applications a ON a.id=c.application_id "
                + "WHERE c.user_id=? AND c.expires_at>CURRENT_TIMESTAMP ORDER BY c.created_at DESC LIMIT 200", user);
    }
    public int purgeExpired(int limit) { return db.update("DELETE FROM permission_contexts WHERE expires_at<CURRENT_TIMESTAMP LIMIT ?", limit); }
}
