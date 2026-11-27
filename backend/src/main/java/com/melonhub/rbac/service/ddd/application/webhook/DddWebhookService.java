package com.melonhub.rbac.service.ddd.application.webhook;

import com.melonhub.rbac.service.ddd.application.shared.DddBoundary;
import com.melonhub.rbac.service.ddd.domain.model.WebhookEndpointAggregate;
import com.melonhub.rbac.service.ddd.domain.repository.PageSlice;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.CryptoGateway;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.DomainEventPublisher;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.JsonCodec;
import com.melonhub.rbac.service.ddd.domain.repository.PlatformGatewayPorts.WebhookTransport;
import com.melonhub.rbac.service.ddd.domain.repository.RbacRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.repository.WebhookRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.value.DomainException;
import com.melonhub.rbac.service.ddd.domain.value.WebhookSignature;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.SaveWebhookCommand;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookCreatedResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookDeliveryResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookDetailResult;
import com.melonhub.rbac.service.port.webhook.WebhookModels.WebhookResult;
import com.melonhub.rbac.service.port.webhook.WebhookUseCase;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DDD Webhook 应用服务：事件白名单、停用测试保护与投递重试状态机委托给 {@link WebhookEndpointAggregate}。
 */
@Service
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class DddWebhookService implements WebhookUseCase, DomainEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(DddWebhookService.class);
    private static final int BATCH = 20;

    private record WebhookPayload(String type, long applicationId, String occurredAt, Map<String, Object> data) {}

    private final WebhookRepositoryPort repository;
    private final RbacRepositoryPort rbacRepository;
    private final JsonCodec json;
    private final CryptoGateway crypto;
    private final WebhookTransport transport;
    private final boolean deliveryEnabled;
    private final int timeoutMillis;
    private final int maxAttempts;
    private final ThreadPoolExecutor senders;

    public DddWebhookService(WebhookRepositoryPort repository, RbacRepositoryPort rbacRepository, JsonCodec json,
                             CryptoGateway crypto, WebhookTransport transport,
                             @Value("${melonhub.webhooks.delivery:true}") boolean deliveryEnabled,
                             @Value("${melonhub.webhooks.timeout-millis:5000}") int timeoutMillis,
                             @Value("${melonhub.webhooks.max-attempts:8}") int maxAttempts,
                             @Value("${melonhub.webhooks.concurrency:4}") int concurrency) {
        this.repository = repository;
        this.rbacRepository = rbacRepository;
        this.json = json;
        this.crypto = crypto;
        this.transport = transport;
        this.deliveryEnabled = deliveryEnabled;
        this.timeoutMillis = timeoutMillis;
        this.maxAttempts = maxAttempts;
        AtomicInteger threads = new AtomicInteger();
        int size = Math.max(1, concurrency);
        this.senders = new ThreadPoolExecutor(size, size, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(BATCH), task -> {
            Thread thread = new Thread(task, "ddd-webhook-sender-" + threads.incrementAndGet());
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
        return repository.webhooks(app).stream()
                .map(w -> new WebhookResult(w.id(), w.name(), w.url(), List.of(w.events().split(",")), w.enabled(),
                        w.createdBy(), w.createdAt(), w.pending(), w.failed(), w.lastDeliveredAt()))
                .toList();
    }

    @Override
    public WebhookDetailResult webhook(long id) {
        return DddBoundary.execute(() -> repository.webhookView(id)
                .map(w -> new WebhookDetailResult(w.id(), w.applicationId(), w.name(), w.url(), List.of(w.events().split(",")),
                        w.enabled(), w.createdBy(), w.createdAt()))
                .orElseThrow(() -> DomainException.missing("Webhook 不存在")));
    }

    @Override
    @Transactional
    public WebhookCreatedResult create(long app, SaveWebhookCommand body, String actor) {
        return DddBoundary.execute(() -> {
            String events = WebhookEndpointAggregate.validateAndJoinEvents(body.events());
            String secret = body.secret() == null ? crypto.generateToken() : body.secret().strip();
            long id = repository.createWebhook(app, body.name().strip(), body.url().strip(), secret, events,
                    !Boolean.FALSE.equals(body.enabled()), actor);
            audit(actor, "WEBHOOK_CREATED", body.name(), app, body.url());
            return new WebhookCreatedResult(id, secret);
        });
    }

    @Override
    @Transactional
    public void update(long id, SaveWebhookCommand body, String actor) {
        DddBoundary.run(() -> {
            WebhookEndpointAggregate existing = repository.findWebhook(id)
                    .orElseThrow(() -> DomainException.missing("Webhook 不存在"));
            String events = WebhookEndpointAggregate.validateAndJoinEvents(body.events());
            if (repository.updateWebhook(id, body.name().strip(), body.url().strip(), events, !Boolean.FALSE.equals(body.enabled())) == 0) {
                throw DomainException.missing("Webhook 不存在");
            }
            if (body.secret() != null) {
                repository.updateWebhookSecret(id, body.secret().strip());
            }
            audit(actor, "WEBHOOK_UPDATED", body.name(), existing.applicationId(),
                    body.url() + (body.secret() != null ? "，已更换签名密钥" : ""));
        });
    }

    @Override
    @Transactional
    public void delete(long id, String actor) {
        DddBoundary.run(() -> {
            WebhookEndpointAggregate existing = repository.findWebhook(id)
                    .orElseThrow(() -> DomainException.missing("Webhook 不存在"));
            repository.deleteWebhook(id);
            audit(actor, "WEBHOOK_DELETED", existing.name(), existing.applicationId(), existing.url());
        });
    }

    @Override
    public PageResult<WebhookDeliveryResult> deliveries(long id, int page, int size) {
        webhook(id);
        PageSlice<WebhookRepositoryPort.DeliveryRecord> slice = repository.deliveries(id, page, size);
        return PageResult.of(slice.items().stream().map(d -> new WebhookDeliveryResult(
                        d.id(), d.eventId(), d.eventType(), d.status(), d.attempts(),
                        d.nextAttemptAt(), d.lastStatus(), d.lastError(), d.createdAt(), d.deliveredAt())).toList(),
                slice.total(), page, size);
    }

    @Override
    @Transactional
    public void test(long id, String actor) {
        DddBoundary.run(() -> {
            WebhookEndpointAggregate hook = repository.findWebhook(id)
                    .orElseThrow(() -> DomainException.missing("Webhook 不存在"));
            hook.assertTestAllowed();
            repository.enqueueTest(id, payload(hook.applicationId(), "webhook.test",
                    Map.of("webhookId", id, "message", "MelonHub 测试事件")));
            audit(actor, "WEBHOOK_TESTED", hook.name(), hook.applicationId(), "");
        });
    }

    @Override
    public void emit(long app, String type, Map<String, Object> data) {
        repository.emit(app, type, payload(app, type, data));
    }

    @Override
    public void emitAll(String type, Map<String, Object> data) {
        repository.emitAll(type, payload(0, type, data));
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

    private String payload(long app, String type, Map<String, Object> data) {
        return json.write(new WebhookPayload(type, app, Instant.now().toString(), data));
    }

    private void dispatch(int limit, Consumer<WebhookRepositoryPort.DueDelivery> sender) {
        try {
            repository.requeueStale(maxAttempts);
            for (WebhookRepositoryPort.DueDelivery delivery : repository.dueDeliveries(limit)) {
                if (repository.claimDelivery(delivery.id(), delivery.attempts()) == 0) {
                    continue;
                }
                sender.accept(delivery);
            }
        } catch (DataAccessException e) {
            log.warn("Webhook delivery skipped: {}", e.getClass().getSimpleName());
        }
    }

    private void submit(WebhookRepositoryPort.DueDelivery delivery) {
        try {
            senders.execute(() -> send(delivery));
        } catch (RejectedExecutionException e) {
            repository.releaseDelivery(delivery.id(), delivery.attempts() + 1);
        }
    }

    private void send(WebhookRepositoryPort.DueDelivery delivery) {
        try {
            post(delivery);
        } catch (DataAccessException e) {
            log.warn("Webhook delivery {} result not recorded: {}", delivery.id(), e.getClass().getSimpleName());
        }
    }

    private void post(WebhookRepositoryPort.DueDelivery delivery) {
        int attempt = delivery.attempts() + 1;
        WebhookEndpointAggregate.DeliveryDecision decision;
        try {
            long timestamp = Instant.now().getEpochSecond();
            String signature = WebhookSignature.header(delivery.secret(), timestamp, delivery.payload());
            int status = transport.post(delivery.url(), delivery.payload(), delivery.eventType(),
                    delivery.eventId(), signature, timeoutMillis);
            decision = WebhookEndpointAggregate.decideOnHttpStatus(attempt, maxAttempts, status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            decision = WebhookEndpointAggregate.decideOnFailure(attempt, maxAttempts, null, "interrupted");
        } catch (Exception e) {
            decision = WebhookEndpointAggregate.decideOnFailure(attempt, maxAttempts, null, e.getClass().getSimpleName());
        }
        switch (decision.type()) {
            case DELIVERED -> repository.markDelivered(delivery.id(), attempt, decision.httpStatus());
            case RETRY -> repository.scheduleRetry(delivery.id(), attempt, decision.delayMinutes(), decision.httpStatus(), decision.error());
            case FAILED -> repository.markFailed(delivery.id(), attempt, decision.httpStatus(), decision.error());
        }
    }

    private void audit(String actor, String action, String target, long app, String detail) {
        rbacRepository.recordAudit(actor, action, target, null, app, detail, crypto.currentClientAddress());
    }
}
