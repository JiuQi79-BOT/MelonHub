-- Roles and permissions belong to an application. Departments only organize users.
-- Old department-bound contexts must be reissued using the application-scoped API.
DELETE FROM permission_contexts;

ALTER TABLE user_roles
 DROP FOREIGN KEY user_roles_ibfk_1,
 DROP FOREIGN KEY user_roles_ibfk_2;
ALTER TABLE role_permissions
 DROP FOREIGN KEY role_permissions_ibfk_1,
 DROP FOREIGN KEY role_permissions_ibfk_2;

-- Keep every role and assignment, including its expiry and grant_id. Do not union
-- permissions from department-specific roles that happen to share a code.
-- The oldest role retains its code; conflicting roles receive a unique suffix.
CREATE TEMPORARY TABLE role_code_conflicts AS
 SELECT r.id FROM roles r
 JOIN (SELECT application_id,code,MIN(id) AS keep_id FROM roles GROUP BY application_id,code) k
 ON k.application_id=r.application_id AND k.code=r.code WHERE r.id<>k.keep_id;
UPDATE roles r JOIN role_code_conflicts c ON c.id=r.id
 SET r.code=CONCAT(LEFT(r.code,58),':legacy:',REPLACE(UUID(),'-',''));
DROP TEMPORARY TABLE role_code_conflicts;

-- Permission codes already identify the operation exposed by a software API.
-- Reuse one definition per code without changing any role's permission-code set.
CREATE TEMPORARY TABLE permission_code_mapping AS
 SELECT p.id,k.keep_id FROM permissions p
 JOIN (SELECT application_id,code,MIN(id) AS keep_id FROM permissions GROUP BY application_id,code) k
 ON k.application_id=p.application_id AND k.code=p.code;
UPDATE role_permissions rp JOIN permission_code_mapping m ON m.id=rp.permission_id
 SET rp.permission_id=m.keep_id;
DELETE p FROM permissions p JOIN permission_code_mapping m ON m.id=p.id WHERE m.id<>m.keep_id;
DROP TEMPORARY TABLE permission_code_mapping;

ALTER TABLE roles
 DROP FOREIGN KEY roles_ibfk_1,
 DROP INDEX department_id,
 DROP INDEX id,
 DROP COLUMN department_id,
 ADD UNIQUE KEY uk_roles_app_code (application_id,code),
 ADD UNIQUE KEY uk_roles_id_app (id,application_id);
ALTER TABLE permissions
 DROP FOREIGN KEY permissions_ibfk_1,
 DROP INDEX department_id,
 DROP INDEX id,
 DROP COLUMN department_id,
 ADD UNIQUE KEY uk_permissions_app_code (application_id,code),
 ADD UNIQUE KEY uk_permissions_id_app (id,application_id);
ALTER TABLE user_roles
 DROP INDEX idx_user_roles_domain,
 DROP COLUMN department_id,
 ADD INDEX idx_user_roles_application (application_id,user_id),
 ADD CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
 ADD CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id,application_id) REFERENCES roles(id,application_id) ON DELETE CASCADE;
ALTER TABLE role_permissions
 DROP INDEX idx_role_permissions_domain,
 DROP COLUMN department_id,
 ADD INDEX idx_role_permissions_application (application_id),
 ADD CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id,application_id) REFERENCES roles(id,application_id) ON DELETE CASCADE,
 ADD CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id,application_id) REFERENCES permissions(id,application_id) ON DELETE CASCADE;

ALTER TABLE permission_contexts
 DROP FOREIGN KEY permission_contexts_ibfk_2,
 DROP INDEX idx_contexts_domain,
 DROP COLUMN department_id,
 ADD INDEX idx_contexts_application (user_id,application_id);
ALTER TABLE users DROP FOREIGN KEY users_ibfk_1, DROP COLUMN active_department_id;

-- Keep historic department snapshots. New review items have a department only
-- when the review used that department as a member filter.
ALTER TABLE access_review_items MODIFY department_id BIGINT NULL;
