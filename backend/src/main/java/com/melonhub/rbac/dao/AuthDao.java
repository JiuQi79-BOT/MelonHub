package com.melonhub.rbac.dao;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuthDao {
    public record AccountRow(String username, String displayName, String role, boolean enabled, String passwordHash, boolean locked,
                             String mfaSecret, long mfaLastStep, long credentialVersion) {}
    public record SessionRow(String username, String displayName, String role, boolean stale) {}
    public record KeyRow(long keyId, long applicationId, boolean valid, Instant expiresAt) {}

    private final JdbcTemplate db;
    public AuthDao(JdbcTemplate db) { this.db = db; }

    public boolean accountExists(String username) {
        return db.queryForObject("SELECT COUNT(*) FROM admin_accounts WHERE username=?", Long.class, username) > 0;
    }
    public void createAccount(String username, String displayName, String role, String hash, boolean enabled) {
        db.update("INSERT INTO admin_accounts(username,display_name,role,password_hash,enabled,password_changed_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
                username, displayName, role, hash, enabled);
    }
    public void resetBootstrap(String username, String hash) {
        db.update("UPDATE admin_accounts SET password_hash=?,role='SUPER_ADMIN',enabled=TRUE,failed_attempts=0,locked_until=NULL,password_changed_at=CURRENT_TIMESTAMP,"
                + "credential_version=credential_version+1 WHERE username=?", hash, username);
    }
    public Optional<AccountRow> account(String username) {
        return db.query("SELECT username,display_name,role,enabled,password_hash,COALESCE(locked_until>CURRENT_TIMESTAMP,FALSE),mfa_secret,mfa_last_step,"
                + "credential_version FROM admin_accounts WHERE username=?",
                (rs, i) -> new AccountRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5), rs.getBoolean(6),
                        rs.getString(7), rs.getLong(8), rs.getLong(9)), username).stream().findFirst();
    }
    /** Counts a failed password and returns whether the account is now locked. */
    public boolean recordFailure(String username, int maxFailures, int lockMinutes) {
        // MySQL evaluates SET assignments left to right, so locked_until must read the old counter first.
        db.update("UPDATE admin_accounts SET locked_until=IF(failed_attempts+1>=?,TIMESTAMPADD(MINUTE,?,CURRENT_TIMESTAMP),locked_until),"
                + "failed_attempts=IF(failed_attempts+1>=?,0,failed_attempts+1) WHERE username=?", maxFailures, lockMinutes, maxFailures, username);
        return Boolean.TRUE.equals(db.queryForObject("SELECT COALESCE(locked_until>CURRENT_TIMESTAMP,FALSE) FROM admin_accounts WHERE username=?", Boolean.class, username));
    }
    public void recordSuccess(String username) {
        db.update("UPDATE admin_accounts SET failed_attempts=0,locked_until=NULL,last_login_at=CURRENT_TIMESTAMP WHERE username=?", username);
    }

    /**
     * Creates the session only while the account still has the credentials the login checked: a password reset,
     * role change, disable or two-factor change committed meanwhile makes this insert nothing. The password hash
     * the login verified is compared too, because an account deleted and created again under the same name starts
     * over at credential version 0; its new hash has a fresh salt and never equals the old one.
     */
    public int createSession(String hash, AccountRow account, Instant expires, String sourceIp) {
        return db.update("INSERT INTO admin_sessions(token_hash,username,expires_at,source_ip,credential_version) "
                + "SELECT ?,username,?,?,credential_version FROM admin_accounts WHERE username=? AND credential_version=? AND password_hash=? "
                + "AND enabled=TRUE AND mfa_secret <=> ?", hash, Timestamp.from(expires), sourceIp, account.username(), account.credentialVersion(),
                account.passwordHash(), account.mfaSecret());
    }
    public Optional<SessionRow> session(String hash, int idleMinutes) {
        return db.query("SELECT a.username,a.display_name,a.role,s.last_seen_at<TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP) FROM admin_sessions s "
                + "JOIN admin_accounts a ON a.username=s.username AND a.credential_version=s.credential_version "
                + "WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP "
                + "AND s.last_seen_at>TIMESTAMPADD(MINUTE,-?,CURRENT_TIMESTAMP) AND a.enabled=TRUE",
                (rs, i) -> new SessionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), hash, idleMinutes).stream().findFirst();
    }
    public void touchSession(String hash) { db.update("UPDATE admin_sessions SET last_seen_at=CURRENT_TIMESTAMP WHERE token_hash=?", hash); }
    public List<Long> accountApplications(String username) {
        return db.queryForList("SELECT application_id FROM admin_account_applications WHERE username=? ORDER BY application_id", Long.class, username);
    }
    public void logout(String hash) { db.update("DELETE FROM admin_sessions WHERE token_hash=?", hash); }
    /** Ends every session, including one a concurrent login is about to create with the old credentials. */
    public void endSessions(String username) {
        db.update("UPDATE admin_accounts SET credential_version=credential_version+1 WHERE username=?", username);
        db.update("DELETE FROM admin_sessions WHERE username=?", username);
    }
    /** After a password change the caller's own session moves to the new credential version; all others end. */
    public void keepOnlySession(String username, String keepHash) {
        db.update("UPDATE admin_sessions s JOIN admin_accounts a ON a.username=s.username SET s.credential_version=a.credential_version "
                + "WHERE s.token_hash=? AND s.username=?", keepHash, username);
        db.update("DELETE FROM admin_sessions WHERE username=? AND token_hash<>?", username, keepHash);
    }
    public int purgeSessions(int idleMinutes, int limit) {
        return db.update("DELETE FROM admin_sessions WHERE expires_at<CURRENT_TIMESTAMP OR last_seen_at<TIMESTAMPADD(MINUTE,-?,CURRENT_TIMESTAMP) LIMIT ?", idleMinutes, limit);
    }

    public List<Map<String,Object>> accounts() {
        return db.queryForList("SELECT a.username,a.display_name AS displayName,a.role,a.enabled,a.failed_attempts AS failedAttempts,"
                + "COALESCE(a.locked_until>CURRENT_TIMESTAMP,FALSE) AS locked,a.locked_until AS lockedUntil,a.last_login_at AS lastLoginAt,"
                + "a.password_changed_at AS passwordChangedAt,a.created_at AS createdAt,"
                + "(SELECT COUNT(*) FROM admin_sessions s WHERE s.username=a.username AND s.expires_at>CURRENT_TIMESTAMP) AS activeSessions,"
                + "a.mfa_secret IS NOT NULL AS mfaEnabled "
                + "FROM admin_accounts a ORDER BY a.created_at,a.username");
    }
    public List<Map<String,Object>> accountApplicationPairs() { return db.queryForList("SELECT username,application_id FROM admin_account_applications"); }
    public int updateAccount(String username, String displayName, String role, boolean enabled) {
        return db.update("UPDATE admin_accounts SET display_name=?,role=?,enabled=? WHERE username=?", displayName, role, enabled, username);
    }
    public void replaceAccountApplications(String username, Collection<Long> applications) {
        db.update("DELETE FROM admin_account_applications WHERE username=?", username);
        db.batchUpdate("INSERT INTO admin_account_applications(username,application_id) VALUES (?,?)",
                applications.stream().map(id -> new Object[]{username, id}).toList());
    }
    public int updatePassword(String username, String hash) {
        return db.update("UPDATE admin_accounts SET password_hash=?,password_changed_at=CURRENT_TIMESTAMP,failed_attempts=0,locked_until=NULL,"
                + "credential_version=credential_version+1 WHERE username=?", hash, username);
    }
    /** Changes the password only if it is still the one the caller proved, so two concurrent changes cannot both win. */
    public int replacePassword(String username, String expectedHash, String hash) {
        return db.update("UPDATE admin_accounts SET password_hash=?,password_changed_at=CURRENT_TIMESTAMP,failed_attempts=0,locked_until=NULL,"
                + "credential_version=credential_version+1 WHERE username=? AND password_hash=?", hash, username, expectedHash);
    }
    public int unlock(String username) { return db.update("UPDATE admin_accounts SET failed_attempts=0,locked_until=NULL WHERE username=?", username); }
    public int deleteAccount(String username) { return db.update("DELETE FROM admin_accounts WHERE username=?", username); }
    public String pendingMfaSecret(String username) {
        return db.queryForList("SELECT mfa_pending_secret FROM admin_accounts WHERE username=?", String.class, username)
                .stream().filter(Objects::nonNull).findFirst().orElse(null);
    }
    public void setMfaPending(String username, String secret) {
        db.update("UPDATE admin_accounts SET mfa_pending_secret=? WHERE username=?", secret, username);
    }
    /** Activates exactly the pending secret the code was checked against; a newer setup in between makes this a no-op. */
    public int confirmMfa(String username, String pendingSecret, long step) {
        return db.update("UPDATE admin_accounts SET mfa_secret=mfa_pending_secret,mfa_pending_secret=NULL,mfa_last_step=? "
                + "WHERE username=? AND mfa_pending_secret=?", step, username, pendingSecret);
    }
    public int disableMfa(String username) {
        return db.update("UPDATE admin_accounts SET mfa_secret=NULL,mfa_pending_secret=NULL,mfa_last_step=0 WHERE username=?", username);
    }
    /**
     * Uses a code step at most once: the row only changes while the step is newer than the last used one, so of two
     * concurrent requests with the same code exactly one gets 1 row back.
     */
    public int consumeMfaStep(String username, String secret, long step) {
        return db.update("UPDATE admin_accounts SET mfa_last_step=? WHERE username=? AND mfa_secret=? AND mfa_last_step<?", step, username, secret, step);
    }
    /** Disabling spends the code the same way, so it cannot race a login that uses the same code. */
    public int disableMfaWithStep(String username, String secret, long step) {
        return db.update("UPDATE admin_accounts SET mfa_secret=NULL,mfa_pending_secret=NULL,mfa_last_step=0 "
                + "WHERE username=? AND mfa_secret=? AND mfa_last_step<?", username, secret, step);
    }

    /** Serializes changes that could remove the last enabled super administrator. */
    public List<String> lockEnabledSuperAdmins() {
        return db.queryForList("SELECT username FROM admin_accounts WHERE role='SUPER_ADMIN' AND enabled=TRUE ORDER BY username FOR UPDATE", String.class);
    }
    public long existingApplications(Collection<Long> ids) {
        if (ids.isEmpty()) return 0;
        return db.queryForObject("SELECT COUNT(*) FROM applications WHERE id IN (" + placeholders(ids.size()) + ")", Long.class, ids.toArray());
    }

    public Optional<KeyRow> applicationKey(String hash) {
        return db.query("SELECT k.id,k.application_id,(a.enabled AND k.revoked_at IS NULL AND (k.expires_at IS NULL OR k.expires_at>CURRENT_TIMESTAMP)),k.expires_at "
                + "FROM application_keys k JOIN applications a ON a.id=k.application_id WHERE k.key_hash=?",
                (rs, i) -> {
                    Timestamp expires = rs.getTimestamp(4);
                    return new KeyRow(rs.getLong(1), rs.getLong(2), rs.getBoolean(3), expires == null ? null : expires.toInstant());
                }, hash).stream().findFirst();
    }
    public void markKeysUsed(Collection<Long> ids) {
        if (ids.isEmpty()) return;
        db.update("UPDATE application_keys SET last_used_at=CURRENT_TIMESTAMP WHERE id IN (" + placeholders(ids.size()) + ")", ids.toArray());
    }

    static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
}
