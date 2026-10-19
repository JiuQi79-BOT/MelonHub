package com.melonhub.rbac.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.dao.RbacDao;
import com.melonhub.rbac.dao.WebhookDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.ClientAddress;
import com.melonhub.rbac.security.Credentials;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signed change notifications. Events are queued as delivery rows inside the writer's transaction (outbox),
 * then pushed with an HMAC-SHA-256 signature and exponential backoff; receivers never trust unsigned payloads.
 */
@Service
public class WebhookService {
    public static final List<String> EVENT_TYPES = List.of(
            "user.enabled", "user.disabled", "user.deleted", "user.roles.changed", "user.roles.expired",
            "user.updated", "user.contexts.revoked", "role.enabled", "role.disabled", "role.deleted", "role.permissions.changed",
            "permission.updated", "permission.deleted",
            "application.enabled", "application.disabled", "apikey.revoked",
            "access.approved", "access.rejected", "review.closed");
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final int BATCH = 20;

    private final WebhookDao dao;
    private final RbacDao rbacDao;
    private final ObjectMapper json;
    private final boolean deliveryEnabled;
    private final int timeoutMillis;
    private final int maxAttempts;
    private final HttpClient http;
    /** Slow receivers occupy these threads only, never the scheduler shared with sealing and cache recovery. */
    private final ThreadPoolExecutor senders;

    public WebhookService(WebhookDao dao, RbacDao rbacDao, ObjectMapper json,
                          @Value("${melonhub.webhooks.delivery:true}") boolean deliveryEnabled,
                          @Value("${melonhub.webhooks.timeout-millis:5000}") int timeoutMillis,
                          @Value("${melonhub.webhooks.max-attempts:8}") int maxAttempts,
                          @Value("${melonhub.webhooks.concurrency:4}") int concurrency) {
        this.dao = dao; this.rbacDao = rbacDao; this.json = json;
        this.deliveryEnabled = deliveryEnabled; this.timeoutMillis = timeoutMillis; this.maxAttempts = maxAttempts;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        var threads = new AtomicInteger();
        int size = Math.max(1, concurrency);
        this.senders = new ThreadPoolExecutor(size, size, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(BATCH), task -> {
            var thread = new Thread(task, "webhook-sender-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.senders.allowCoreThreadTimeOut(true);
    }

    @PreDestroy void stopSenders() throws InterruptedException {
        senders.shutdown();
        senders.awaitTermination(timeoutMillis + 1000L, TimeUnit.MILLISECONDS);
    }

    public List<Map<String,Object>> webhooks(long app) {
        var rows = dao.webhooks(app);
        for (var row : rows) row.put("events", List.of(((String) row.get("events")).split(",")));
        return rows;
    }
    public Map<String,Object> webhook(long id) { return dao.webhook(id).orElseThrow(() -> ApiException.missing("Webhook 不存在")); }

    @Transactional public Map<String,Object> create(long app, Requests.Webhook body, String actor) {
        String secret = body.secret() == null ? Credentials.token() : body.secret().strip();
        long id = dao.create(app, body.name().strip(), body.url().strip(), secret, events(body.events()), !Boolean.FALSE.equals(body.enabled()), actor);
        audit(actor, "WEBHOOK_CREATED", body.name(), app, body.url());
        var result = new LinkedHashMap<String,Object>();
        result.put("id", id);
        result.put("secret", secret);
        return result;
    }
    @Transactional public void update(long id, Requests.Webhook body, String actor) {
        var existing = webhook(id);
        if (dao.update(id, body.name().strip(), body.url().strip(), events(body.events()), !Boolean.FALSE.equals(body.enabled())) == 0)
            throw ApiException.missing("Webhook 不存在");
        if (body.secret() != null) dao.updateSecret(id, body.secret().strip());
        audit(actor, "WEBHOOK_UPDATED", body.name(), number(existing.get("applicationId")), body.url() + (body.secret() != null ? "，已更换签名密钥" : ""));
    }
    @Transactional public void delete(long id, String actor) {
        var existing = webhook(id);
        dao.delete(id);
        audit(actor, "WEBHOOK_DELETED", (String) existing.get("name"), number(existing.get("applicationId")), (String) existing.get("url"));
    }
    public Map<String,Object> deliveries(long id, int page, int size) { webhook(id); return RbacService.page(dao.deliveries(id, page, size), page, size); }
    @Transactional public void test(long id, String actor) {
        var hook = webhook(id);
        if (!Boolean.TRUE.equals(hook.get("enabled"))) throw ApiException.conflict("Webhook 已停用，请先启用");
        dao.enqueueTest(id, payload(number(hook.get("applicationId")), "webhook.test", Map.of("webhookId", id, "message", "MelonHub 测试事件")));
        audit(actor, "WEBHOOK_TESTED", (String) hook.get("name"), number(hook.get("applicationId")), "");
    }

    /** Queues one signed delivery per matching webhook; must run inside the writer's transaction. */
    public void emit(long app, String type, Map<String,Object> data) {
        dao.emit(app, type, payload(app, type, data));
    }

    /** Platform event for every application; applicationId is 0 in the payload. */
    public void emitAll(String type, Map<String,Object> data) {
        dao.emitAll(type, payload(0, type, data));
    }

    private String payload(long app, String type, Map<String,Object> data) {
        var body = new LinkedHashMap<String,Object>();
        body.put("type", type);
        body.put("applicationId", app);
        body.put("occurredAt", Instant.now().toString());
        body.put("data", data);
        try { return json.writeValueAsString(body); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    /**
     * Claims only as many due deliveries as the sender pool can queue and returns without waiting for any HTTP
     * call, so a receiver that hangs until the timeout cannot hold up other scheduled jobs.
     */
    @Scheduled(initialDelay = 10_000, fixedDelay = 5_000)
    public void scheduledDeliveries() {
        if (!deliveryEnabled) return;
        int capacity = senders.getQueue().remainingCapacity();
        if (capacity > 0) dispatch(Math.min(BATCH, capacity), this::submit);
    }

    /** Sends every due delivery once on the calling thread; one failing endpoint never blocks the others. */
    public void deliverNow() { dispatch(BATCH, this::send); }

    private void dispatch(int limit, BiConsumer<Long, Map<String,Object>> sender) {
        try {
            dao.requeueStale();
            for (var delivery : dao.dueDeliveries(limit)) {
                long id = number(delivery.get("id"));
                if (dao.claim(id, ((Number) delivery.get("attempts")).intValue()) == 0) continue;
                sender.accept(id, delivery);
            }
        } catch (DataAccessException e) {
            log.warn("Webhook delivery skipped: {}", e.getClass().getSimpleName());
        }
    }

    private void submit(long id, Map<String,Object> delivery) {
        try {
            senders.execute(() -> send(id, delivery));
        } catch (RejectedExecutionException e) {
            dao.release(id, attempt(delivery));
        }
    }

    private void send(long id, Map<String,Object> delivery) {
        try {
            post(id, delivery);
        } catch (DataAccessException e) {
            // The SENDING lease expires and the delivery is retried; nothing else can be done without the database.
            log.warn("Webhook delivery {} result not recorded: {}", id, e.getClass().getSimpleName());
        }
    }

    /** The attempt count the claim wrote: one more than the row had when it was read. */
    private static int attempt(Map<String,Object> delivery) { return ((Number) delivery.get("attempts")).intValue() + 1; }

    private void post(long id, Map<String,Object> delivery) {
        String payload = (String) delivery.get("payload");
        int attempt = attempt(delivery);
        int status;
        try {
            long timestamp = Instant.now().getEpochSecond();
            String signature = "t=" + timestamp + ",v1=" + hmacSha256((String) delivery.get("secret"), timestamp + "." + payload);
            var request = HttpRequest.newBuilder(URI.create((String) delivery.get("url")))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json")
                    .header("MelonHub-Event", (String) delivery.get("eventType"))
                    .header("MelonHub-Delivery", (String) delivery.get("eventId"))
                    .header("MelonHub-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            var response = http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            try {
                status = response.get(timeoutMillis, TimeUnit.MILLISECONDS).statusCode();
            } finally {
                response.cancel(true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reschedule(id, attempt, null, "interrupted");
            return;
        } catch (Exception e) {
            reschedule(id, attempt, null, e.getClass().getSimpleName());
            return;
        }
        if (status >= 200 && status < 300) dao.delivered(id, attempt, status);
        else reschedule(id, attempt, status, "HTTP " + status);
    }

    private void reschedule(long id, int attempt, Integer status, String error) {
        if (attempt >= maxAttempts) { dao.failed(id, attempt, status, error); return; }
        dao.retry(id, attempt, (int) Math.min(60, 1L << attempt - 1), status, error);
    }

    /** Signature shared with receivers: v1 = HMAC-SHA-256(secret, "{t}.{body}"), hex encoded. */
    public static String hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) { throw new IllegalStateException(e); }
    }

    private static String events(List<String> events) {
        var joined = new StringJoiner(",");
        for (String event : events) {
            String trimmed = event.strip();
            if (!trimmed.equals("*") && !EVENT_TYPES.contains(trimmed)) throw ApiException.bad("未知事件类型: " + trimmed + "，可用 * 或 " + String.join(", ", EVENT_TYPES));
            joined.add(trimmed);
        }
        return joined.toString();
    }
    private void audit(String actor, String action, String target, long app, String detail) {
        rbacDao.audit(actor, action, target, null, app, detail, ClientAddress.current());
    }
    private static long number(Object value) { return ((Number) value).longValue(); }
}
