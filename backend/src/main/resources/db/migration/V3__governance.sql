-- Time-bound role assignments. Expired rows stop counting at once; maintenance removes them afterwards.
ALTER TABLE user_roles
 ADD COLUMN expires_at TIMESTAMP NULL,
 ADD COLUMN granted_by VARCHAR(100) NOT NULL DEFAULT '',
 ADD COLUMN granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 ADD INDEX idx_user_roles_expiry (expires_at);

-- Requests per second for one application; NULL uses the platform default and 0 means unlimited.
ALTER TABLE applications ADD COLUMN rate_limit INT NULL;

-- Employees ask for roles through their business system; administrators approve or reject in the console.
CREATE TABLE access_requests (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 department_id BIGINT NOT NULL,
 application_id BIGINT NOT NULL,
 role_id BIGINT NOT NULL,
 reason VARCHAR(500) NOT NULL DEFAULT '',
 duration_days INT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 requested_by VARCHAR(100) NOT NULL,
 decided_by VARCHAR(100) NULL,
 decided_at TIMESTAMP NULL,
 decision_comment VARCHAR(500) NOT NULL DEFAULT '',
 grant_expires_at TIMESTAMP NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_access_requests_app (application_id, status, created_at),
 INDEX idx_access_requests_user (user_id, status),
 INDEX idx_access_requests_status (status, created_at),
 CONSTRAINT fk_access_requests_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
 CONSTRAINT fk_access_requests_department FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE CASCADE,
 CONSTRAINT fk_access_requests_application FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE CASCADE,
 CONSTRAINT fk_access_requests_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Periodic access reviews: a snapshot of assignments that reviewers keep or revoke, applied when closed.
CREATE TABLE access_reviews (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 name VARCHAR(100) NOT NULL,
 application_id BIGINT NOT NULL,
 department_id BIGINT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
 due_at TIMESTAMP NULL,
 created_by VARCHAR(100) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 closed_by VARCHAR(100) NULL,
 closed_at TIMESTAMP NULL,
 summary VARCHAR(500) NOT NULL DEFAULT '',
 INDEX idx_access_reviews_app (application_id, status),
 CONSTRAINT fk_access_reviews_application FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE CASCADE,
 CONSTRAINT fk_access_reviews_department FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE access_review_items (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 review_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 role_id BIGINT NOT NULL,
 department_id BIGINT NOT NULL,
 decision VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 comment VARCHAR(500) NOT NULL DEFAULT '',
 decided_by VARCHAR(100) NULL,
 decided_at TIMESTAMP NULL,
 applied BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE KEY uk_access_review_items (review_id, user_id, role_id),
 INDEX idx_access_review_items_decision (review_id, decision),
 CONSTRAINT fk_review_items_review FOREIGN KEY (review_id) REFERENCES access_reviews(id) ON DELETE CASCADE,
 CONSTRAINT fk_review_items_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
 CONSTRAINT fk_review_items_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Signed change notifications. Deliveries are written in the same transaction as the change (outbox).
CREATE TABLE webhooks (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 application_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 url VARCHAR(500) NOT NULL,
 secret VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 events VARCHAR(500) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_by VARCHAR(100) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_webhooks_application (application_id),
 CONSTRAINT fk_webhooks_application FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE webhook_deliveries (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 webhook_id BIGINT NOT NULL,
 event_id CHAR(36) COLLATE utf8mb4_bin NOT NULL,
 event_type VARCHAR(64) NOT NULL,
 payload TEXT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_status INT NULL,
 last_error VARCHAR(300) NOT NULL DEFAULT '',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 delivered_at TIMESTAMP NULL,
 INDEX idx_webhook_deliveries_due (status, next_attempt_at),
 INDEX idx_webhook_deliveries_webhook (webhook_id, id),
 INDEX idx_webhook_deliveries_created (created_at),
 CONSTRAINT fk_webhook_deliveries_webhook FOREIGN KEY (webhook_id) REFERENCES webhooks(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- TOTP two-factor authentication for console accounts; a code step is accepted at most once.
ALTER TABLE admin_accounts
 ADD COLUMN mfa_secret VARCHAR(64) COLLATE utf8mb4_bin NULL,
 ADD COLUMN mfa_pending_secret VARCHAR(64) COLLATE utf8mb4_bin NULL,
 ADD COLUMN mfa_last_step BIGINT NOT NULL DEFAULT 0;

-- Tamper-evident audit: events are chained with SHA-256 in id order once they are safely committed.
ALTER TABLE audit_events
 ADD COLUMN prev_hash CHAR(64) COLLATE utf8mb4_bin NULL,
 ADD COLUMN chain_hash CHAR(64) COLLATE utf8mb4_bin NULL;
CREATE TABLE audit_seal (
 id TINYINT PRIMARY KEY,
 last_id BIGINT NOT NULL,
 last_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 sealed_count BIGINT NOT NULL DEFAULT 0,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
INSERT INTO audit_seal(id, last_id, last_hash) VALUES (1, 0, REPEAT('0', 64));
