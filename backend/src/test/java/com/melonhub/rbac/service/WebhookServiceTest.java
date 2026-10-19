package com.melonhub.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.dao.RbacDao;
import com.melonhub.rbac.dao.WebhookDao;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebhookServiceTest {
    @Test void responseHeadersDoNotEndTheDeliveryDeadline() throws Exception {
        var headers = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 10);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            headers.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        var dao = mock(WebhookDao.class);
        when(dao.dueDeliveries(20)).thenReturn(List.of(Map.of("id", 1L, "attempts", 0, "payload", "{}", "secret", "test-secret",
                "eventType", "user.disabled", "eventId", "test", "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/")));
        when(dao.claim(1L, 0)).thenReturn(1);
        var service = new WebhookService(dao, mock(RbacDao.class), new ObjectMapper(), true, 500, 8, 1);
        try {
            long start = System.nanoTime();
            service.deliverNow();
            assertThat(headers.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            verify(dao).retry(1L, 1, 1, null, "TimeoutException");
            verify(dao, never()).delivered(anyLong(), anyInt(), anyInt());
        } finally {
            release.countDown();
            server.stop(0);
            service.stopSenders();
        }
    }
}
