package com.melonhub.rbac.dao;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuthDao {
    private final JdbcTemplate db;
    public AuthDao(JdbcTemplate db) { this.db = db; }
    public void saveAdmin(String username, String hash) { db.update("INSERT INTO admin_accounts(username,password_hash) VALUES (?,?) ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash)", username, hash); }
    public void clearExpiredSessions() { db.update("DELETE FROM admin_sessions WHERE expires_at < CURRENT_TIMESTAMP"); }
    public List<String> passwordHashes(String username) { return db.queryForList("SELECT password_hash FROM admin_accounts WHERE username=?", String.class, username); }
    public void createSession(String hash, String username, Instant expires) { db.update("INSERT INTO admin_sessions VALUES (?,?,?)", hash, username, Timestamp.from(expires)); }
    public List<String> sessionUsers(String hash) { return db.queryForList("SELECT username FROM admin_sessions WHERE token_hash=? AND expires_at>CURRENT_TIMESTAMP", String.class, hash); }
    public List<Long> applications(String hash) { return db.queryForList("SELECT id FROM applications WHERE api_key_hash=? AND enabled=TRUE", Long.class, hash); }
    public void logout(String hash) { db.update("DELETE FROM admin_sessions WHERE token_hash=?", hash); }
}
