package com.melonhub.rbac.dao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executes queue SQL against an isolated in-memory database; never connects to the configured MySQL. */
class WebhookDaoTest {
    private JdbcTemplate db;
    private WebhookDao dao;

    @BeforeEach void setUp() {
        db = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE webhooks (id BIGINT PRIMARY KEY,enabled BOOLEAN NOT NULL)");
        db.execute("CREATE TABLE webhook_deliveries (id BIGINT PRIMARY KEY,webhook_id BIGINT NOT NULL,"
                + "status VARCHAR(20) NOT NULL,attempts INT NOT NULL,created_at TIMESTAMP NOT NULL,"
                + "next_attempt_at TIMESTAMP NOT NULL,last_error VARCHAR(300) DEFAULT '',"
                + "last_status INT,delivered_at TIMESTAMP)");
        db.update("INSERT INTO webhooks(id,enabled) VALUES (1,TRUE)");
        dao = new WebhookDao(db);
    }

    @AfterEach void tearDown() {
        if (db != null) db.execute("SHUTDOWN");
    }

    @Test void retentionPreservesPendingAndInFlightDeliveries() {
        insert(1, "PENDING", 2, 31, -1);
        insert(2, "SENDING", 3, 31, 5);
        insert(3, "DELIVERED", 1, 31, -1);
        insert(4, "FAILED", 8, 31, -1);
        insert(5, "DELIVERED", 1, 1, -1);

        assertThat(dao.purge(30, 5000)).isEqualTo(2);
        assertThat(db.queryForList("SELECT id FROM webhook_deliveries ORDER BY id", Long.class))
                .containsExactly(1L, 2L, 5L);
        // The retained in-flight delivery can still record its outcome after cleanup.
        assertThat(dao.delivered(2, 3, 200)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 8})
    void expiredFinalAttemptFailsInsteadOfStartingAnotherAttempt(int maxAttempts) {
        insert(1, "SENDING", maxAttempts, 1, -1);
        dao.requeueStale(maxAttempts);

        assertThat(status(1)).isEqualTo("FAILED");
        assertThat(dao.claim(1, maxAttempts)).isZero();
        assertThat(dao.delivered(1, maxAttempts, 200)).isZero();
        assertThat(db.queryForObject("SELECT attempts FROM webhook_deliveries WHERE id=1", Integer.class))
                .isEqualTo(maxAttempts);
        assertThat(db.queryForObject("SELECT last_error FROM webhook_deliveries WHERE id=1", String.class))
                .contains("lease expired");
    }

    @Test void expiredEarlierAttemptRetriesWhileLiveLeaseIsPreserved() {
        insert(1, "SENDING", 3, 1, -1);
        insert(2, "SENDING", 3, 1, 5);
        assertThat(dao.requeueStale(8)).isEqualTo(1);

        assertThat(status(1)).isEqualTo("PENDING");
        assertThat(dao.claim(1, 3)).isEqualTo(1);
        assertThat(dao.delivered(1, 3, 200)).isZero();
        assertThat(dao.delivered(1, 4, 200)).isEqualTo(1);
        assertThat(status(2)).isEqualTo("SENDING");
    }

    private void insert(long id, String status, int attempts, int ageDays, int leaseMinutes) {
        db.update("INSERT INTO webhook_deliveries(id,webhook_id,status,attempts,created_at,next_attempt_at) "
                + "VALUES (?,1,?,?,TIMESTAMPADD(DAY,-?,CURRENT_TIMESTAMP),TIMESTAMPADD(MINUTE,?,CURRENT_TIMESTAMP))",
                id, status, attempts, ageDays, leaseMinutes);
    }

    private String status(long id) {
        return db.queryForObject("SELECT status FROM webhook_deliveries WHERE id=?", String.class, id);
    }
}
