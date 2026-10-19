-- Console sessions remember the credential version they were created under. A password change or reset, a
-- role change and disabling an account bump the version, so a login that raced the change cannot leave a
-- working session behind.
ALTER TABLE admin_accounts ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE admin_sessions ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;

-- Every assignment instance gets its own identifier, renewed whenever the role is granted again or its expiry
-- is changed, so a review can tell the assignment it snapshotted from a later re-grant of the same role.
ALTER TABLE user_roles ADD COLUMN grant_id CHAR(36) COLLATE utf8mb4_bin NULL;
UPDATE user_roles SET grant_id=UUID();
ALTER TABLE user_roles MODIFY grant_id CHAR(36) COLLATE utf8mb4_bin NOT NULL DEFAULT (UUID());

-- Governance history outlives the user, role and department it mentions: names are copied into the record and
-- the reference becomes NULL when the referenced row is deleted.
ALTER TABLE access_requests
 ADD COLUMN user_name VARCHAR(100) NOT NULL DEFAULT '',
 ADD COLUMN external_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
 ADD COLUMN role_code VARCHAR(100) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
 ADD COLUMN role_name VARCHAR(100) NOT NULL DEFAULT '',
 ADD COLUMN department_name VARCHAR(100) NOT NULL DEFAULT '',
 ADD COLUMN pending_role_id BIGINT NULL;
UPDATE access_requests r JOIN users u ON u.id=r.user_id SET r.user_name=u.name, r.external_id=u.external_id;
UPDATE access_requests r JOIN roles ro ON ro.id=r.role_id SET r.role_code=ro.code, r.role_name=ro.name;
UPDATE access_requests r JOIN departments d ON d.id=r.department_id SET r.department_name=d.name;

-- At most one pending request per user and role: pending_role_id is the role while the request is pending and
-- NULL afterwards. Duplicates left by earlier concurrent submissions keep only the oldest one.
UPDATE access_requests r
 JOIN (SELECT user_id, role_id, MIN(id) AS keep_id FROM access_requests WHERE status='PENDING' GROUP BY user_id, role_id HAVING COUNT(*)>1) d
 ON r.user_id=d.user_id AND r.role_id=d.role_id
 SET r.status='CANCELLED', r.decided_by='system', r.decided_at=CURRENT_TIMESTAMP, r.decision_comment='重复的待审批申请'
 WHERE r.status='PENDING' AND r.id<>d.keep_id;
UPDATE access_requests SET pending_role_id=role_id WHERE status='PENDING';
ALTER TABLE access_requests ADD UNIQUE INDEX uk_access_requests_pending (user_id, pending_role_id);

ALTER TABLE access_requests
 DROP FOREIGN KEY fk_access_requests_user,
 DROP FOREIGN KEY fk_access_requests_role,
 DROP FOREIGN KEY fk_access_requests_department;
ALTER TABLE access_requests
 MODIFY user_id BIGINT NULL,
 MODIFY role_id BIGINT NULL,
 MODIFY department_id BIGINT NULL,
 ADD CONSTRAINT fk_access_requests_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
 ADD CONSTRAINT fk_access_requests_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE SET NULL,
 ADD CONSTRAINT fk_access_requests_department FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE SET NULL;

ALTER TABLE access_reviews ADD COLUMN department_name VARCHAR(100) NOT NULL DEFAULT '';
UPDATE access_reviews v JOIN departments d ON d.id=v.department_id SET v.department_name=d.name;
ALTER TABLE access_reviews DROP FOREIGN KEY fk_access_reviews_department;
ALTER TABLE access_reviews
 ADD CONSTRAINT fk_access_reviews_department FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE SET NULL;

-- grant_id is the assignment the item was taken from; closing only revokes that same assignment and marks the
-- item skipped when the role was removed or granted again in the meantime. Items created before this migration
-- have no grant_id and are revoked as before.
ALTER TABLE access_review_items
 ADD COLUMN grant_id CHAR(36) COLLATE utf8mb4_bin NULL,
 ADD COLUMN skipped BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN user_name VARCHAR(100) NOT NULL DEFAULT '',
 ADD COLUMN external_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
 ADD COLUMN role_code VARCHAR(100) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
 ADD COLUMN role_name VARCHAR(100) NOT NULL DEFAULT '',
 ADD COLUMN department_name VARCHAR(100) NOT NULL DEFAULT '';
UPDATE access_review_items i JOIN users u ON u.id=i.user_id SET i.user_name=u.name, i.external_id=u.external_id;
UPDATE access_review_items i JOIN roles r ON r.id=i.role_id SET i.role_code=r.code, i.role_name=r.name;
UPDATE access_review_items i JOIN departments d ON d.id=i.department_id SET i.department_name=d.name;
ALTER TABLE access_review_items
 DROP FOREIGN KEY fk_review_items_user,
 DROP FOREIGN KEY fk_review_items_role;
ALTER TABLE access_review_items
 MODIFY user_id BIGINT NULL,
 MODIFY role_id BIGINT NULL,
 ADD CONSTRAINT fk_review_items_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
 ADD CONSTRAINT fk_review_items_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE SET NULL;

-- Audit records sealed from here on use a length-prefixed canonical form (v2) that also binds the position;
-- older records keep verifying with the original form.
ALTER TABLE audit_seal ADD COLUMN v2_from_seq BIGINT NOT NULL DEFAULT 1;
UPDATE audit_seal SET v2_from_seq=last_seq+1 WHERE id=1;

-- Retention cleanup under V3 removed the oldest rows without recording an anchor, and V4 started from the genesis
-- anchor. The first remaining sealed row still names its predecessor's hash, which becomes the anchor.
UPDATE audit_seal s
 JOIN (SELECT seal_seq, prev_hash FROM audit_events WHERE seal_seq IS NOT NULL ORDER BY seal_seq LIMIT 1) f
 SET s.anchor_seq=f.seal_seq-1, s.anchor_hash=f.prev_hash
 WHERE s.id=1 AND s.anchor_seq=0 AND f.prev_hash IS NOT NULL AND f.prev_hash<>REPEAT('0', 64);
