package com.melonhub.rbac.service.structured.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.infrastructure.persistence.entity.Webhook;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AuditEventRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.WebhookRepository;
import com.melonhub.rbac.infrastructure.security.ClientAddress;
import com.melonhub.rbac.infrastructure.security.Credentials;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.webhook.WebhookModels.SaveWebhookCommand;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookCreatedResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookDeliveryResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookDetailResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookResult;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structured Webhook 服务：订阅维护、事务外盒写库与 HMAC-SHA256 签名投递。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "structured", matchIfMissing = true)
public class StructuredWebhookService implements WebhookUseCase {
    private static final Logger log = LoggerFactory.getLogger(StructuredWebhookService.class);
    private static final int BATCH = 20;

    private record WebhookPayload(String type, long applicationId, String occurredAt, Map<String, Object> data) {}

    private final WebhookRepository webhooks;
    private final WebhookDeliveryRepository deliveries;
    private final ApplicationRepository applications;
    private final AuditEventRepository auditEvents;
    private final ObjectMapper json;
    private final boolean deliveryEnabled;
    private final int timeoutMillis;
    private final int maxAttempts;
    private final HttpClient http;
    private final ThreadPoolExecutor senders;

    public StructuredWebhookService(WebhookRepository webhooks, WebhookDeliveryRepository deliveries,
                                    ApplicationRepository applications, AuditEventRepository auditEvents,
                                    ObjectMapper json,
                                    @Value("${melonhub.webhooks.delivery:true}") boolean deliveryEnabled,
                                    @Value("${melonhub.webhooks.timeout-millis:5000}") int timeoutMillis,
                                    @Value("${melonhub.webhooks.max-attempts:8}") int maxAttempts,
                                    @Value("${melonhub.webhooks.concurrency:4}") int concurrency) {
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.applications = applications;
        this.auditEvents = auditEvents;
        this.json = json;
        this.deliveryEnabled = deliveryEnabled;
        this.timeoutMillis = timeoutMillis;
        this.maxAttempts = maxAttempts;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        AtomicInteger threads = new AtomicInteger();
        int size = Math.max(1, concurrency);
        this.senders = new ThreadPoolExecutor(size, size, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(BATCH), task -> {
            Thread thread = new Thread(task, "webhook-sender-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.senders.allowCoreThreadTimeOut(true);
    }

    @Override
    @PreDestroy
    public void stopSenders() throws InterruptedException {
        senders.shutdown();
        senders.awaitTermination(timeoutMillis + 1000L, TimeUnit.MILLISECONDS);
    }

    @Override
    public List<WebhookResult> webhooks(long app) {
        List<WebhookRepository.WebhookSummary> rows = webhooks.findSummariesByApplicationId(app);
        Map<Long, WebhookDeliveryRepository.WebhookDeliveryStats> stats = new HashMap<>();
        for (WebhookDeliveryRepository.WebhookDeliveryStats s : deliveries.findStatsByApplicationId(app)) {
            stats.put(s.getWebhookId(), s);
        }
        return rows.stream().map(w -> {
            WebhookDeliveryRepository.WebhookDeliveryStats s = stats.get(w.getId());
            return new WebhookResult(w.getId(), w.getName(), w.getUrl(), List.of(w.getEvents().split(",")), w.isEnabled(),
                    w.getCreatedBy(), w.getCreatedAt(),
                    s == null ? 0L : s.getPending(),
                    s == null ? 0L : s.getFailed(),
                    s == null ? null : s.getLastDeliveredAt());
        }).toList();
    }

    @Override
    public WebhookDetailResult webhook(long id) {
        WebhookRepository.WebhookDetail w = webhooks.findDetailById(id)
                .orElseThrow(() -> ApiException.missing("Webhook 不存在"));
        return new WebhookDetailResult(w.getId(), w.getApplicationId(), w.getName(), w.getUrl(),
                List.of(w.getEvents().split(",")), w.isEnabled(), w.getCreatedBy(), w.getCreatedAt());
    }

    @Override
    @Transactional
    public WebhookCreatedResult create(long app, SaveWebhookCommand body, String actor) {
        String secret = body.secret() == null ? Credentials.token() : body.secret().strip();
        long id = webhooks.saveAndFlush(new Webhook()
                .setApplication(applications.getReferenceById(app))
                .setName(body.name().strip())
                .setUrl(body.url().strip())
                .setSecret(secret)
                .setEvents(events(body.events()))
                .setEnabled(!Boolean.FALSE.equals(body.enabled()))
                .setCreatedBy(actor)).getId();
        audit(actor, "WEBHOOK_CREATED", body.name(), app, body.url());
        return new WebhookCreatedResult(id, secret);
    }

    @Override
    @Transactional
    public void update(long id, SaveWebhookCommand body, String actor) {
        WebhookDetailResult existing = webhook(id);
        if (webhooks.updateProfile(id, body.name().strip(), body.url().strip(), events(body.events()),
                !Boolean.FALSE.equals(body.enabled())) == 0) {
            throw ApiException.missing("Webhook 不存在");
        }
        if (body.secret() != null) {
            webhooks.updateSecret(id, body.secret().strip());
        }
        audit(actor, "WEBHOOK_UPDATED", body.name(), existing.applicationId(),
                body.url() + (body.secret() != null ? "，已更换签名密钥" : ""));
    }

    @Override
    @Transactional
    public void delete(long id, String actor) {
        WebhookDetailResult existing = webhook(id);
        webhooks.deleteByIdReturningCount(id);
        audit(actor, "WEBHOOK_DELETED", existing.name(), existing.applicationId(), existing.url());
    }

    @Override
    public PageResult<WebhookDeliveryResult> deliveries(long id, int page, int size) {
        webhook(id);
        Page<WebhookDeliveryRepository.DeliverySummary> p =
                deliveries.findPageByWebhookId(id, PageRequest.of(Math.max(0, page - 1), Math.max(1, size)));
        return PageResult.of(p.getContent().stream().map(d -> new WebhookDeliveryResult(
                        d.getId(), d.getEventId(), d.getEventType(), d.getStatus(), d.getAttempts(),
                        d.getNextAttemptAt(), d.getLastStatus(), d.getLastError(), d.getCreatedAt(), d.getDeliveredAt())).toList(),
                p.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public void test(long id, String actor) {
        WebhookDetailResult hook = webhook(id);
        if (!hook.enabled()) {
            throw ApiException.conflict("Webhook 已停用，请先启用");
        }
        deliveries.enqueueTest(webhooks.getReferenceById(id),
                payload(hook.applicationId(), "webhook.test", Map.of("webhookId", id, "message", "MelonHub 测试事件")));
        audit(actor, "WEBHOOK_TESTED", hook.name(), hook.applicationId(), "");
    }

    @Override
    public void emit(long app, String type, Map<String, Object> data) {
        deliveries.enqueueMatching(webhooks.findEnabledByApplicationId(app), type, payload(app, type, data));
    }

    @Override
    public void emitAll(String type, Map<String, Object> data) {
        deliveries.enqueueMatching(webhooks.findAllEnabled(), type, payload(0, type, data));
    }

    private String payload(long app, String type, Map<String, Object> data) {
        try {
            return json.writeValueAsString(new WebhookPayload(type, app, Instant.now().toString(), data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    @Scheduled(initialDelay = 10_000, fixedDelay = 5_000)
    public void scheduledDeliveries() {
        if (!deliveryEnabled) {
            return;
        }
        int capacity = senders.getQueue().remainingCapacity();
        if (capacity > 0) {
            dispatch(Math.min(BATCH, capacity), this::submit);
        }
    }

    @Override
    public void deliverNow() {
        dispatch(BATCH, this::send);
    }

    private void dispatch(int limit, Consumer<WebhookDeliveryRepository.DueDelivery> sender) {
        try {
            deliveries.requeueStale(maxAttempts);
            for (WebhookDeliveryRepository.DueDelivery delivery : deliveries.findDueDeliveries(Limit.of(limit))) {
                if (deliveries.claim(delivery.getId(), delivery.getAttempts()) == 0) {
                    continue;
                }
                sender.accept(delivery);
            }
        } catch (DataAccessException e) {
            log.warn("Webhook delivery skipped: {}", e.getClass().getSimpleName());
        }
    }

    private void submit(WebhookDeliveryRepository.DueDelivery delivery) {
        try {
            senders.execute(() -> send(delivery));
        } catch (RejectedExecutionException e) {
            deliveries.releaseClaim(delivery.getId(), delivery.getAttempts() + 1);
        }
    }

    private void send(WebhookDeliveryRepository.DueDelivery delivery) {
        try {
            post(delivery);
        } catch (DataAccessException e) {
            log.warn("Webhook delivery {} result not recorded: {}", delivery.getId(), e.getClass().getSimpleName());
        }
    }

    private void post(WebhookDeliveryRepository.DueDelivery delivery) {
        int attempt = delivery.getAttempts() + 1;
        int status;
        try {
            long timestamp = Instant.now().getEpochSecond();
            String signature = "t=" + timestamp + ",v1="
                    + WebhookUseCase.hmacSha256(delivery.getSecret(), timestamp + "." + delivery.getPayload());
            HttpRequest request = HttpRequest.newBuilder(URI.create(delivery.getUrl()))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json")
                    .header("MelonHub-Event", delivery.getEventType())
                    .header("MelonHub-Delivery", delivery.getEventId())
                    .header("MelonHub-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.getPayload(), StandardCharsets.UTF_8)).build();
            CompletableFuture<HttpResponse<Void>> response = http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            try {
                status = response.get(timeoutMillis, TimeUnit.MILLISECONDS).statusCode();
            } finally {
                response.cancel(true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reschedule(delivery.getId(), attempt, null, "interrupted");
            return;
        } catch (Exception e) {
            reschedule(delivery.getId(), attempt, null, e.getClass().getSimpleName());
            return;
        }
        if (status >= 200 && status < 300) {
            deliveries.markDelivered(delivery.getId(), attempt, status);
        } else {
            reschedule(delivery.getId(), attempt, status, "HTTP " + status);
        }
    }

    private void reschedule(long id, int attempt, Integer status, String error) {
        if (attempt >= maxAttempts) {
            deliveries.fail(id, attempt, status, error);
            return;
        }
        deliveries.retry(id, attempt, (int) Math.min(60, 1L << (attempt - 1)), status, error);
    }

    private static String events(List<String> events) {
        StringJoiner joined = new StringJoiner(",");
        for (String event : events) {
            String trimmed = event.strip();
            if (!trimmed.equals("*") && !EVENT_TYPES.contains(trimmed)) {
                throw ApiException.bad("未知事件类型: " + trimmed + "，可用 * 或 " + String.join(", ", EVENT_TYPES));
            }
            joined.add(trimmed);
        }
        return joined.toString();
    }

    private void audit(String actor, String action, String target, long app, String detail) {
        auditEvents.recordAudit(actor, action, target, null, app, detail, ClientAddress.current());
    }
}
