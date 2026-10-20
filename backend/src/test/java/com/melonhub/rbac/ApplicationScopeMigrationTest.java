package com.melonhub.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.melonhub.rbac.security.Credentials;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/** Exercises the real migrations on disposable, prefixed tables inside the guarded test schema. */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers=CloudTestDatabaseGuard.class)
class ApplicationScopeMigrationTest {
    @Autowired DataSource dataSource;

    @Test void migratingDepartmentScopesKeepsRoleGrantsAndHistoryWithoutCombiningRolePermissions() throws Exception {
        String prefix = "m6_" + Credentials.token().substring(0, 8).replace('-', 'a').replace('_', 'b') + "_";
        String[] names = {"V1__rbac.sql", "V2__enterprise.sql", "V3__governance.sql", "V4__audit_seal_order.sql",
                "V5__governance_history.sql", "V6__application_scope.sql"};
        var scripts = new ArrayList<String>();
        var tables = new LinkedHashSet<String>();
        var identifiers = new LinkedHashSet<String>();
        for (String name : names) {
            String sql = new ClassPathResource("db/migration/" + name).getContentAsString(StandardCharsets.UTF_8);
            scripts.add(sql);
            var created = Pattern.compile("CREATE (?:TEMPORARY )?TABLE (\\w+)").matcher(sql);
            while (created.find()) {
                identifiers.add(created.group(1));
                if (!created.group().contains("TEMPORARY")) tables.add(created.group(1));
            }
            var foreignKeys = Pattern.compile("(?:CONSTRAINT|FOREIGN KEY) (\\w+)").matcher(sql);
            while (foreignKeys.find()) identifiers.add(foreignKeys.group(1));
        }
        Pattern tokens = Pattern.compile("\\b(" + String.join("|", identifiers) + ")\\b");
        try (Connection connection = dataSource.getConnection()) {
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            try {
                for (int i = 0; i < 5; i++) execute(connection, tokens.matcher(scripts.get(i)).replaceAll(prefix + "$1"));
                execute(connection, tokens.matcher("""
                    INSERT INTO departments(id,name,code) VALUES (1,'First','d1'),(2,'Second','d2');
                    INSERT INTO applications(id,name,code) VALUES (1,'One','a1'),(2,'Two','a2');
                    INSERT INTO users(id,name,external_id,active_department_id) VALUES (1,'One','u1',1),(2,'Two','u2',2),(3,'Both','u3',1);
                    INSERT INTO memberships VALUES (1,1),(2,2),(3,1),(3,2);
                    INSERT INTO roles(id,department_id,application_id,name,code) VALUES
                     (10,1,1,'Reader one','reader'),(20,2,1,'Reader two','reader'),(30,1,2,'Other app','reader');
                    INSERT INTO permissions(id,department_id,application_id,name,code,resource,action) VALUES
                     (10,1,1,'Read one','item:read','item','read'),(20,2,1,'Read two','item:read','item','read'),
                     (21,2,1,'Write','item:write','item','write'),(30,1,2,'Read other','item:read','item','read');
                    INSERT INTO role_permissions VALUES (10,10,1,1),(20,20,2,1),(20,21,2,1),(30,30,1,2);
                    INSERT INTO user_roles(user_id,role_id,department_id,application_id,expires_at,grant_id) VALUES
                     (1,10,1,1,NULL,'grant-one'),(2,20,2,1,'2030-01-01 00:00:00','grant-two'),
                     (3,10,1,1,NULL,'grant-both-one'),(3,20,2,1,'2030-01-02 00:00:00','grant-both-two');
                    INSERT INTO permission_contexts(id,user_id,department_id,application_id,context_version,expires_at)
                     VALUES (REPEAT('a',64),1,1,1,0,'2030-01-01 00:00:00');
                    INSERT INTO context_roles VALUES (REPEAT('a',64),10);
                    INSERT INTO access_requests(id,user_id,department_id,application_id,role_id,pending_role_id,requested_by,department_name,role_code)
                     VALUES (1,1,2,1,20,20,'test','Second','reader');
                    INSERT INTO access_reviews(id,name,application_id,department_id,created_by,department_name)
                     VALUES (1,'Review',1,2,'test','Second');
                    INSERT INTO access_review_items(review_id,user_id,role_id,department_id,grant_id,department_name,role_code)
                     VALUES (1,2,20,2,'grant-two','Second','reader');
                    INSERT INTO audit_events(id,actor,action,target,department_id,application_id,prev_hash,chain_hash,seal_seq)
                     VALUES (1,'test','USER_ROLES_UPDATED','u2',2,1,REPEAT('0',64),REPEAT('b',64),1);
                    UPDATE audit_seal SET last_id=1,last_seq=1,last_hash=REPEAT('b',64),sealed_count=1;
                    """).replaceAll(prefix + "$1"));
                var grants = db.queryForList("SELECT user_id,role_id,application_id,expires_at,grant_id FROM " + prefix + "user_roles ORDER BY user_id,role_id");
                var audit = db.queryForList("SELECT * FROM " + prefix + "audit_events");
                var seal = db.queryForList("SELECT * FROM " + prefix + "audit_seal");

                execute(connection, tokens.matcher(scripts.get(5)).replaceAll(prefix + "$1"));

                assertThat(db.queryForList("SELECT user_id,role_id,application_id,expires_at,grant_id FROM " + prefix + "user_roles ORDER BY user_id,role_id")).isEqualTo(grants);
                assertThat(db.queryForObject("SELECT code FROM " + prefix + "roles WHERE id=10", String.class)).isEqualTo("reader");
                assertThat(db.queryForObject("SELECT code FROM " + prefix + "roles WHERE id=20", String.class)).startsWith("reader:legacy:");
                assertThat(db.queryForObject("SELECT code FROM " + prefix + "roles WHERE id=30", String.class)).isEqualTo("reader");
                assertThat(db.queryForObject("SELECT COUNT(*) FROM " + prefix + "permissions", Long.class)).isEqualTo(3);
                assertThat(permissionCodes(db, prefix, 10)).containsExactly("item:read");
                assertThat(permissionCodes(db, prefix, 20)).containsExactly("item:read", "item:write");
                assertThat(db.queryForObject("SELECT COUNT(*) FROM " + prefix + "permission_contexts", Long.class)).isZero();
                assertThat(db.queryForObject("SELECT COUNT(*) FROM " + prefix + "context_roles", Long.class)).isZero();
                assertThat(db.queryForList("SELECT * FROM " + prefix + "audit_events")).isEqualTo(audit);
                assertThat(db.queryForList("SELECT * FROM " + prefix + "audit_seal")).isEqualTo(seal);
                assertThat(db.queryForMap("SELECT role_id,role_code,department_name FROM " + prefix + "access_requests WHERE id=1"))
                        .containsEntry("role_id", 20L).containsEntry("role_code", "reader").containsEntry("department_name", "Second");
                assertThat(db.queryForObject("SELECT grant_id FROM " + prefix + "access_review_items WHERE role_id=20", String.class)).isEqualTo("grant-two");
                db.update("DELETE FROM " + prefix + "memberships WHERE user_id=2");
                assertThat(db.queryForObject("SELECT grant_id FROM " + prefix + "user_roles WHERE user_id=2 AND role_id=20", String.class)).isEqualTo("grant-two");
            } finally {
                var reverse = new ArrayList<>(tables);
                java.util.Collections.reverse(reverse);
                for (String table : reverse) db.execute("DROP TABLE IF EXISTS " + prefix + table);
            }
        }
    }

    private static List<String> permissionCodes(JdbcTemplate db, String prefix, long role) {
        return db.queryForList("SELECT p.code FROM " + prefix + "permissions p JOIN " + prefix
                + "role_permissions rp ON rp.permission_id=p.id WHERE rp.role_id=? ORDER BY p.code", String.class, role);
    }

    private static void execute(Connection connection, String sql) {
        ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
    }
}
