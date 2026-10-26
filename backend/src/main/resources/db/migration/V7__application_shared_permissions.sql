-- Preserve every definition in duplicate groups, including metadata and its former owner.
CREATE TABLE permission_merge_archive AS
SELECT p.*, canonical.canonical_id
FROM permissions p JOIN (
    SELECT application_id,code,MIN(id) AS canonical_id FROM permissions
    GROUP BY application_id,code HAVING COUNT(*)>1
) canonical ON canonical.application_id=p.application_id AND canonical.code=p.code;

CREATE TEMPORARY TABLE permission_merge_map AS
SELECT p.id,g.canonical_id FROM permissions p JOIN (
    SELECT application_id,code,MIN(id) AS canonical_id FROM permissions GROUP BY application_id,code
) g ON g.application_id=p.application_id AND g.code=p.code;

ALTER TABLE role_permissions DROP FOREIGN KEY fk_role_permission_owner;
ALTER TABLE permissions DROP FOREIGN KEY fk_permission_owner;

-- The role may already select the canonical definition; keep one pair per role and permission.
INSERT IGNORE INTO role_permissions(role_id,permission_id,application_id)
SELECT rp.role_id,m.canonical_id,rp.application_id FROM role_permissions rp
JOIN permission_merge_map m ON m.id=rp.permission_id WHERE m.id<>m.canonical_id;
DELETE rp FROM role_permissions rp JOIN permission_merge_map m ON m.id=rp.permission_id
WHERE m.id<>m.canonical_id;
DELETE p FROM permissions p JOIN permission_merge_map m ON m.id=p.id WHERE m.id<>m.canonical_id;

ALTER TABLE permissions DROP INDEX uq_permission_role_code, DROP INDEX uq_permission_owner,
    DROP COLUMN role_id,
    ADD CONSTRAINT uq_permission_application_code UNIQUE (application_id,code),
    ADD CONSTRAINT uq_permission_application UNIQUE (id,application_id),
    ADD CONSTRAINT fk_permission_application FOREIGN KEY (application_id) REFERENCES applications(id);
ALTER TABLE role_permissions ADD CONSTRAINT fk_role_permission_application
    FOREIGN KEY (permission_id,application_id) REFERENCES permissions(id,application_id) ON DELETE CASCADE;
DROP TEMPORARY TABLE permission_merge_map;
