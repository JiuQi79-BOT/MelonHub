package com.melonhub.rbac.dao;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookDao {
    private final JdbcTemplate db;
    public WebhookDao(JdbcTemplate db) { this.db = db; }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    public long create(long app, String name, String url, String secret, String events, boolean enabled, String createdBy) {
        return insert("INSERT INTO webhooks(application_id,name,url,secret,events,enabled,created_by) VALUES (?,?,?,?,?,?,?)",
                app, name, url, secret, events, enabled, createdBy);
    }
    public List<Map<String,Object>> webhooks(long app) {
        return db.queryForList("SELECT w.id,w.name,w.url,w.events,w.enabled,w.created_by AS createdBy,w.created_at AS createdAt,"
                + "(SELECT COUNT(*) FROM webhook_deliveries d WHERE d.webhook_id=w.id AND d.status='PENDING') AS pending,"
                + "(SELECT COUNT(*) FROM webhook_deliveries d WHERE d.webhook_id=w.id AND d.status='FAILED') AS failed,"
                + "(SELECT MAX(d.delivered_at) FROM webhook_deliveries d WHERE d.webhook_id=w.id AND d.status='DELIVERED') AS lastDeliveredAt "
                + "FROM webhooks w WHERE w.application_id=? ORDER BY w.id", app);
    }
    public Optional<Map<String,Object>> webhook(long id) {
        return db.queryForList("SELECT id,application_id AS applicationId,name,url,events,enabled,created_by AS createdBy,created_at AS createdAt "
                + "FROM webhooks WHERE id=?", id).stream().findFirst();
    }
    public int update(long id, String name, String url, String events, boolean enabled) {
        return db.update("UPDATE webhooks SET name=?,url=?,events=?,enabled=? WHERE id=?", name, url, events, enabled, id);
    }
    public int updateSecret(long id, String secret) { return db.update("UPDATE webhooks SET secret=? WHERE id=?", secret, id); }
    public int delete(long id) { return db.update("DELETE FROM webhooks WHERE id=?", id); }

    /** One delivery row per matching enabled webhook, written in the caller's transaction (transactional outbox). */
    public int emit(long app, String type, String payload) {
        return db.update("INSERT INTO webhook_deliveries(webhook_id,event_id,event_type,payload) SELECT id,UUID(),?,? FROM webhooks "
                + "WHERE application_id=? AND enabled=TRUE AND (FIND_IN_SET('*',events) OR FIND_IN_SET(?,events))", type, payload, app, type);
    }
    /** Platform-wide events (e.g. user disabled) fan out to every matching enabled webhook. */
    public int emitAll(String type, String payload) {
        return db.update("INSERT INTO webhook_deliveries(webhook_id,event_id,event_type,payload) SELECT id,UUID(),?,? FROM webhooks "
                + "WHERE enabled=TRUE AND (FIND_IN_SET('*',events) OR FIND_IN_SET(?,events))", type, payload, type);
    }
    public void enqueueTest(long webhook, String payload) {
        db.update("INSERT INTO webhook_deliveries(webhook_id,event_id,event_type,payload) VALUES (?,UUID(),'webhook.test',?)", webhook, payload);
    }

    /** Deliveries of a disabled webhook stay queued until it is enabled again. */
    public List<Map<String,Object>> dueDeliveries(int limit) {
        return db.queryForList("SELECT d.id,d.event_id AS eventId,d.event_type AS eventType,d.payload,d.attempts,w.url,w.secret "
                + "FROM webhook_deliveries d JOIN webhooks w ON w.id=d.webhook_id "
                + "WHERE d.status='PENDING' AND d.next_attempt_at<=CURRENT_TIMESTAMP AND w.enabled=TRUE ORDER BY d.id LIMIT ?", limit);
    }
    /**
     * Claims the delivery exactly as it was read: still due and with the same attempt count. Another instance that
     * already sent it and scheduled a retry changed both, so a stale read cannot send early or reuse the count.
     * next_attempt_at doubles as a lease: a crashed worker leaves SENDING rows that the requeue takes back.
     * The new attempt count identifies this claim when the result is written.
     */
    public int claim(long id, int attempts) {
        return db.update("UPDATE webhook_deliveries SET status='SENDING',attempts=attempts+1,next_attempt_at=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP) "
                + "WHERE id=? AND status='PENDING' AND attempts=? AND next_attempt_at<=CURRENT_TIMESTAMP "
                + "AND EXISTS (SELECT 1 FROM webhooks w WHERE w.id=webhook_deliveries.webhook_id AND w.enabled=TRUE)", id, attempts);
    }
    /** Hands a claimed delivery back without counting an attempt, e.g. when it could not be scheduled. */
    public void release(long id, int attempt) {
        db.update("UPDATE webhook_deliveries SET status='PENDING',attempts=attempts-1,next_attempt_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND status='SENDING' AND attempts=?", id, attempt);
    }
    /** An expired final claim is terminal too: a crashed worker must not bypass the attempt limit. */
    public int requeueStale(int maxAttempts) {
        return db.update("UPDATE webhook_deliveries SET status=CASE WHEN attempts>=? THEN 'FAILED' ELSE 'PENDING' END,"
                + "last_error=CASE WHEN attempts>=? THEN 'Delivery lease expired after final attempt' ELSE last_error END "
                + "WHERE status='SENDING' AND next_attempt_at<=CURRENT_TIMESTAMP", maxAttempts, maxAttempts);
    }
    // Results are written only by the claim that is still current: a worker whose lease expired and whose delivery
    // was claimed again finds a different attempt count and leaves the newer claim alone.
    public int delivered(long id, int attempt, int status) {
        return db.update("UPDATE webhook_deliveries SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP,last_status=?,last_error='' "
                + "WHERE id=? AND status='SENDING' AND attempts=?", status, id, attempt);
    }
    public int retry(long id, int attempt, int delayMinutes, Integer status, String error) {
        return db.update("UPDATE webhook_deliveries SET status='PENDING',next_attempt_at=TIMESTAMPADD(MINUTE,?,CURRENT_TIMESTAMP),last_status=?,last_error=? "
                + "WHERE id=? AND status='SENDING' AND attempts=?", delayMinutes, status, truncate(error), id, attempt);
    }
    public int failed(long id, int attempt, Integer status, String error) {
        return db.update("UPDATE webhook_deliveries SET status='FAILED',last_status=?,last_error=? WHERE id=? AND status='SENDING' AND attempts=?",
                status, truncate(error), id, attempt);
    }
    public RbacDao.Page deliveries(long webhook, int page, int size) {
        var where = new StringBuilder(" WHERE webhook_id=?");
        long total = db.queryForObject("SELECT COUNT(*) FROM webhook_deliveries" + where, Long.class, webhook);
        if (total == 0) return new RbacDao.Page(new ArrayList<>(), 0);
        List<Object> args = new ArrayList<>(Arrays.asList(webhook, size, (long) (page - 1) * size));
        return new RbacDao.Page(db.queryForList("SELECT id,event_id AS eventId,event_type AS eventType,status,attempts,next_attempt_at AS nextAttemptAt,"
                + "last_status AS lastStatus,last_error AS lastError,created_at AS createdAt,delivered_at AS deliveredAt "
                + "FROM webhook_deliveries" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray()), total);
    }
    public int purge(int days, int limit) {
        return db.update("DELETE FROM webhook_deliveries WHERE created_at<TIMESTAMPADD(DAY,-?,CURRENT_TIMESTAMP) "
                + "AND status IN ('DELIVERED','FAILED') LIMIT ?", days, limit);
    }
    public long backlog() {
        return db.queryForObject("SELECT COUNT(*) FROM webhook_deliveries WHERE status='PENDING'", Long.class);
    }
    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
