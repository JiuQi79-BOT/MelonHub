CREATE TABLE role_inheritances (
 child_role_id BIGINT NOT NULL,
 parent_role_id BIGINT NOT NULL,
 department_id BIGINT NOT NULL,
 application_id BIGINT NOT NULL,
 PRIMARY KEY (child_role_id, parent_role_id),
 CONSTRAINT ck_role_inheritance_distinct CHECK (child_role_id <> parent_role_id),
 CONSTRAINT fk_role_inheritance_child_domain FOREIGN KEY (child_role_id, department_id, application_id)
   REFERENCES roles(id, department_id, application_id) ON DELETE CASCADE,
 CONSTRAINT fk_role_inheritance_parent_domain FOREIGN KEY (parent_role_id, department_id, application_id)
   REFERENCES roles(id, department_id, application_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE INDEX idx_role_inheritances_parent ON role_inheritances(parent_role_id, department_id, application_id);
