-- Platform administrators: roles, lockout, idle timeout and per-application delegation.
ALTER TABLE admin_accounts
 ADD COLUMN display_name VARCHAR(100) NOT NULL DEFAULT '',
 ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'SUPER_ADMIN',
 ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0,
 ADD COLUMN locked_until TIMESTAMP NULL,
 ADD COLUMN last_login_at TIMESTAMP NULL,
 ADD COLUMN password_changed_at TIMESTAMP NULL,
 ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
-- Existing accounts were the single bootstrap administrator; new rows must name their role.
ALTER TABLE admin_accounts ALTER COLUMN role SET DEFAULT 'AUDITOR';

CREATE TABLE admin_account_applications (
 username VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 application_id BIGINT NOT NULL,
 PRIMARY KEY (username, application_id),
 CONSTRAINT fk_account_apps_account FOREIGN KEY (username) REFERENCES admin_accounts(username) ON DELETE CASCADE,
 CONSTRAINT fk_account_apps_application FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE CASCADE
) ENGINE=InnoDB;

DELETE FROM admin_sessions WHERE username NOT IN (SELECT username FROM admin_accounts);
ALTER TABLE admin_sessions MODIFY username VARCHAR(100) COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE admin_sessions
 ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 ADD COLUMN last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 ADD COLUMN source_ip VARCHAR(64) NOT NULL DEFAULT '',
 ADD INDEX idx_admin_sessions_expiry (expires_at),
 ADD INDEX idx_admin_sessions_user (username);
ALTER TABLE admin_sessions
 ADD CONSTRAINT fk_admin_sessions_account FOREIGN KEY (username) REFERENCES admin_accounts(username) ON DELETE CASCADE;

-- Several named keys per application allow zero-downtime rotation and expiry.
CREATE TABLE application_keys (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 application_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 key_prefix VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
 key_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 created_by VARCHAR(100) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at TIMESTAMP NULL,
 last_used_at TIMESTAMP NULL,
 revoked_at TIMESTAMP NULL,
 UNIQUE KEY uk_application_keys_hash (key_hash),
 INDEX idx_application_keys_app (application_id, revoked_at),
 CONSTRAINT fk_application_keys_application FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE CASCADE
) ENGINE=InnoDB;
INSERT INTO application_keys (application_id, name, key_prefix, key_hash, created_by)
 SELECT id, '默认密钥', 'mh_', api_key_hash, 'migration' FROM applications;
ALTER TABLE applications DROP COLUMN api_key_hash;
ALTER TABLE applications ADD COLUMN owner VARCHAR(100) NOT NULL DEFAULT '';

ALTER TABLE permission_contexts
 ADD INDEX idx_contexts_expiry (expires_at),
 ADD INDEX idx_contexts_domain (user_id, department_id, application_id);
ALTER TABLE role_permissions ADD INDEX idx_role_permissions_domain (department_id, application_id);
ALTER TABLE user_roles ADD INDEX idx_user_roles_domain (department_id, application_id, user_id);

ALTER TABLE audit_events
 ADD COLUMN detail VARCHAR(1000) NOT NULL DEFAULT '',
 ADD COLUMN source_ip VARCHAR(64) NOT NULL DEFAULT '',
 ADD INDEX idx_audit_created (created_at),
 ADD INDEX idx_audit_application (application_id, created_at),
 ADD INDEX idx_audit_actor (actor, created_at),
 ADD INDEX idx_audit_action (action, created_at);
