package com.melonhub.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/** Runs the real V7 script against uniquely named V6 fixture tables in the guarded test database. */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers=CloudTestDatabaseGuard.class)
class ApplicationPermissionMigrationIntegrationTest {
    @Autowired JdbcTemplate db;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void upgradesEmptyAndPopulatedRoleOwnedPermissions(boolean populated) throws Exception {
        String prefix = "v7_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "_";
        var tables = List.of("permission_merge_archive", "permission_merge_map", "role_permissions", "user_roles", "permissions", "roles", "applications");
        try {
            execute(prefix, "CREATE TABLE applications(id BIGINT PRIMARY KEY)");
            execute(prefix, "CREATE TABLE roles(id BIGINT PRIMARY KEY,application_id BIGINT NOT NULL,UNIQUE(id,application_id))");
            execute(prefix, "CREATE TABLE permissions(id BIGINT PRIMARY KEY,role_id BIGINT NOT NULL,application_id BIGINT NOT NULL,"
                    + "code VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,name VARCHAR(100) NOT NULL,resource VARCHAR(100),action VARCHAR(64),"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE KEY uq_permission_role_code(role_id,code),"
                    + "UNIQUE KEY uq_permission_owner(id,role_id,application_id),"
                    + "CONSTRAINT fk_permission_owner FOREIGN KEY(role_id,application_id) REFERENCES roles(id,application_id) ON DELETE CASCADE)");
            execute(prefix, "CREATE TABLE role_permissions(role_id BIGINT NOT NULL,permission_id BIGINT NOT NULL,application_id BIGINT NOT NULL,"
                    + "PRIMARY KEY(role_id,permission_id),CONSTRAINT fk_role_permission_role FOREIGN KEY(role_id,application_id) REFERENCES roles(id,application_id) ON DELETE CASCADE,"
                    + "CONSTRAINT fk_role_permission_owner FOREIGN KEY(permission_id,role_id,application_id) REFERENCES permissions(id,role_id,application_id) ON DELETE CASCADE)");
            execute(prefix, "CREATE TABLE user_roles(user_id BIGINT,role_id BIGINT,expires_at TIMESTAMP NULL,grant_id VARCHAR(36),PRIMARY KEY(user_id,role_id))");
            execute(prefix, "INSERT INTO applications VALUES(1),(2)");
            execute(prefix, "INSERT INTO roles VALUES(1,1),(2,1),(3,2)");
            if (populated) {
                execute(prefix, "INSERT INTO permissions(id,role_id,application_id,code,name,resource,action) VALUES"
                        + "(1,1,1,'read','Canonical','item','read'),(2,2,1,'read','Conflicting metadata','other','read'),"
                        + "(3,3,2,'read','Other application','item','read'),(4,2,1,'unused','Unassigned','item','unused')");
                execute(prefix, "INSERT INTO role_permissions VALUES(1,1,1),(2,2,1),(3,3,2)");
                execute(prefix, "INSERT INTO user_roles VALUES(1,2,'2027-01-01','original-grant')");
            }
            String script = new ClassPathResource("db/migration/V7__application_shared_permissions.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            String rewritten = rewrite(prefix, script);
            db.execute((ConnectionCallback<Void>) connection -> {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(rewritten.getBytes(StandardCharsets.UTF_8)));
                return null;
            });
            if (populated) {
                assertThat(db.queryForList("SELECT id FROM " + prefix + "permissions ORDER BY id", Long.class)).containsExactly(1L, 3L, 4L);
                assertThat(db.queryForList("SELECT permission_id FROM " + prefix + "role_permissions WHERE application_id=1 ORDER BY role_id", Long.class)).containsExactly(1L, 1L);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM " + prefix + "permission_merge_archive", Long.class)).isEqualTo(2L);
                assertThat(db.queryForObject("SELECT name FROM " + prefix + "permission_merge_archive WHERE id=2", String.class)).isEqualTo("Conflicting metadata");
                assertThat(db.queryForObject("SELECT name FROM " + prefix + "permissions WHERE id=1", String.class)).isEqualTo("Canonical");
                assertThat(db.queryForObject("SELECT grant_id FROM " + prefix + "user_roles WHERE role_id=2 AND expires_at='2027-01-01'", String.class)).isEqualTo("original-grant");
                assertThatThrownBy(() -> execute(prefix, "INSERT INTO permissions(id,application_id,code,name) VALUES(5,1,'read','Duplicate')"))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> execute(prefix, "INSERT INTO role_permissions VALUES(1,3,1)"))
                        .isInstanceOf(DataIntegrityViolationException.class);
                execute(prefix, "INSERT INTO permissions(id,application_id,code,name) VALUES(5,1,'Read','Case-sensitive')");
                execute(prefix, "DELETE FROM roles WHERE id=1");
                assertThat(db.queryForObject("SELECT permission_id FROM " + prefix + "role_permissions WHERE role_id=2", Long.class)).isEqualTo(1L);
                execute(prefix, "DELETE FROM permissions WHERE id=1");
                assertThat(db.queryForObject("SELECT COUNT(*) FROM " + prefix + "role_permissions WHERE application_id=1", Long.class)).isZero();
            } else {
                assertThat(db.queryForObject("SELECT COUNT(*) FROM " + prefix + "permission_merge_archive", Long.class)).isZero();
                execute(prefix, "INSERT INTO permissions(id,application_id,code,name) VALUES(1,1,'read','Shared without owner')");
                execute(prefix, "INSERT INTO role_permissions VALUES(1,1,1),(2,1,1)");
            }
        } finally {
            for (String table : tables) db.execute("DROP TABLE IF EXISTS " + prefix + table);
        }
    }

    private void execute(String prefix, String sql) { db.execute(rewrite(prefix, sql)); }

    private static String rewrite(String prefix, String sql) {
        for (String name : List.of("permission_merge_archive", "permission_merge_map", "role_permissions", "user_roles", "permissions", "roles", "applications",
                "fk_permission_owner", "fk_role_permission_owner", "fk_role_permission_role", "fk_permission_application", "fk_role_permission_application"))
            sql = sql.replaceAll("\\b" + Pattern.quote(name) + "\\b", prefix + name);
        return sql;
    }
}
