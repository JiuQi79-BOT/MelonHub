CREATE TABLE departments (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 name VARCHAR(100) NOT NULL,
 code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE applications (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 name VARCHAR(100) NOT NULL,
 code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 description VARCHAR(500) NOT NULL DEFAULT '',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 api_key_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE users (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 name VARCHAR(100) NOT NULL,
 external_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 email VARCHAR(150) NOT NULL DEFAULT '',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 active_department_id BIGINT NULL,
 context_version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (active_department_id) REFERENCES departments(id)
) ENGINE=InnoDB;
CREATE TABLE memberships (
 user_id BIGINT NOT NULL,
 department_id BIGINT NOT NULL,
 PRIMARY KEY (user_id,department_id),
 FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
 FOREIGN KEY (department_id) REFERENCES departments(id)
) ENGINE=InnoDB;
CREATE TABLE roles (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 department_id BIGINT NOT NULL,
 application_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 code VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 description VARCHAR(500) NOT NULL DEFAULT '',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (department_id,application_id,code),
 UNIQUE (id,department_id,application_id),
 FOREIGN KEY (department_id) REFERENCES departments(id),
 FOREIGN KEY (application_id) REFERENCES applications(id)
) ENGINE=InnoDB;
CREATE TABLE permissions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 department_id BIGINT NOT NULL,
 application_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 code VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 resource VARCHAR(100) NOT NULL,
 action VARCHAR(64) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (department_id,application_id,code),
 UNIQUE (id,department_id,application_id),
 FOREIGN KEY (department_id) REFERENCES departments(id),
 FOREIGN KEY (application_id) REFERENCES applications(id)
) ENGINE=InnoDB;
CREATE TABLE user_roles (
 user_id BIGINT NOT NULL,
 role_id BIGINT NOT NULL,
 department_id BIGINT NOT NULL,
 application_id BIGINT NOT NULL,
 PRIMARY KEY (user_id,role_id),
 FOREIGN KEY (user_id,department_id) REFERENCES memberships(user_id,department_id) ON DELETE CASCADE,
 FOREIGN KEY (role_id,department_id,application_id) REFERENCES roles(id,department_id,application_id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE role_permissions (
 role_id BIGINT NOT NULL,
 permission_id BIGINT NOT NULL,
 department_id BIGINT NOT NULL,
 application_id BIGINT NOT NULL,
 PRIMARY KEY (role_id,permission_id),
 FOREIGN KEY (role_id,department_id,application_id) REFERENCES roles(id,department_id,application_id) ON DELETE CASCADE,
 FOREIGN KEY (permission_id,department_id,application_id) REFERENCES permissions(id,department_id,application_id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE permission_contexts (
 id CHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
 user_id BIGINT NOT NULL,
 department_id BIGINT NOT NULL,
 application_id BIGINT NOT NULL,
 context_version BIGINT NOT NULL,
 expires_at TIMESTAMP NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
 FOREIGN KEY (department_id) REFERENCES departments(id),
 FOREIGN KEY (application_id) REFERENCES applications(id),
 INDEX (user_id,expires_at)
) ENGINE=InnoDB;
CREATE TABLE context_roles (
 context_id CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 role_id BIGINT NOT NULL,
 PRIMARY KEY (context_id,role_id),
 FOREIGN KEY (context_id) REFERENCES permission_contexts(id) ON DELETE CASCADE,
 FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE admin_accounts (
 username VARCHAR(100) COLLATE utf8mb4_bin PRIMARY KEY,
 password_hash VARCHAR(100) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE admin_sessions (
 token_hash CHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
 username VARCHAR(100) NOT NULL,
 expires_at TIMESTAMP NOT NULL
) ENGINE=InnoDB;
CREATE TABLE audit_events (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 actor VARCHAR(100) NOT NULL,
 action VARCHAR(100) NOT NULL,
 target VARCHAR(200) NOT NULL,
 department_id BIGINT NULL,
 application_id BIGINT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX (department_id,application_id,created_at)
) ENGINE=InnoDB;
