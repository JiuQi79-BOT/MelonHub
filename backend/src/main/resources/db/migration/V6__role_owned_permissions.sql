-- Keep unassigned legacy definitions for review without granting them to anyone.
CREATE TABLE permission_migration_archive AS
SELECT p.* FROM permissions p
WHERE NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.permission_id=p.id);

ALTER TABLE permissions ADD COLUMN role_id BIGINT NULL;
UPDATE permissions p JOIN (
    SELECT permission_id,MIN(role_id) AS role_id FROM role_permissions GROUP BY permission_id
) owners ON owners.permission_id=p.id SET p.role_id=owners.role_id;

-- The old unique key prevents independent copies with the same code.
ALTER TABLE permissions ADD INDEX idx_permission_department (department_id);
ALTER TABLE permissions DROP INDEX department_id;
INSERT INTO permissions(department_id,application_id,role_id,name,code,resource,action,created_at)
SELECT p.department_id,p.application_id,rp.role_id,p.name,p.code,p.resource,p.action,p.created_at
FROM permissions p JOIN role_permissions rp ON rp.permission_id=p.id
WHERE rp.role_id<>p.role_id;
UPDATE role_permissions rp
JOIN permissions old_permission ON old_permission.id=rp.permission_id
JOIN permissions owned ON owned.role_id=rp.role_id AND owned.code=old_permission.code
    AND owned.department_id=rp.department_id AND owned.application_id=rp.application_id
SET rp.permission_id=owned.id;
DELETE FROM permissions WHERE role_id IS NULL;

-- Duplicate legacy role codes are kept as separate roles to avoid widening anyone's access.
CREATE TABLE role_migration_archive AS SELECT * FROM roles;
UPDATE roles r JOIN (
    SELECT application_id,code FROM roles GROUP BY application_id,code HAVING COUNT(*)>1
) duplicates ON duplicates.application_id=r.application_id AND duplicates.code=r.code
SET r.code=CONCAT(LEFT(r.code,50),':legacy:',r.id);

ALTER TABLE user_roles DROP FOREIGN KEY user_roles_ibfk_1, DROP FOREIGN KEY user_roles_ibfk_2;
ALTER TABLE role_permissions DROP FOREIGN KEY role_permissions_ibfk_1, DROP FOREIGN KEY role_permissions_ibfk_2;
ALTER TABLE permissions DROP FOREIGN KEY permissions_ibfk_1, DROP FOREIGN KEY permissions_ibfk_2;
ALTER TABLE roles DROP FOREIGN KEY roles_ibfk_1;
ALTER TABLE roles DROP INDEX department_id, DROP INDEX id, DROP COLUMN department_id,
    ADD CONSTRAINT uq_role_application_code UNIQUE (application_id,code),
    ADD CONSTRAINT uq_role_application UNIQUE (id,application_id);
ALTER TABLE permissions DROP INDEX id, DROP COLUMN department_id,
    MODIFY role_id BIGINT NOT NULL,
    ADD CONSTRAINT uq_permission_role_code UNIQUE (role_id,code),
    ADD CONSTRAINT uq_permission_owner UNIQUE (id,role_id,application_id),
    ADD CONSTRAINT fk_permission_owner FOREIGN KEY (role_id,application_id)
        REFERENCES roles(id,application_id) ON DELETE CASCADE;
ALTER TABLE role_permissions DROP COLUMN department_id,
    ADD CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id,application_id)
        REFERENCES roles(id,application_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_role_permission_owner FOREIGN KEY (permission_id,role_id,application_id)
        REFERENCES permissions(id,role_id,application_id) ON DELETE CASCADE;
-- department_id records where a grant was made; it is never an authorization boundary.
ALTER TABLE user_roles MODIFY department_id BIGINT NULL,
    ADD CONSTRAINT fk_assignment_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_assignment_role FOREIGN KEY (role_id,application_id) REFERENCES roles(id,application_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_assignment_origin FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE SET NULL;
ALTER TABLE permission_contexts DROP FOREIGN KEY permission_contexts_ibfk_2,
    MODIFY department_id BIGINT NULL,
    ADD CONSTRAINT fk_context_department FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE SET NULL;
-- Old contexts captured department-limited active roles. Force clients to acquire the new application view.
DELETE FROM context_roles;
DELETE FROM permission_contexts;
UPDATE users SET context_version=context_version+1;
