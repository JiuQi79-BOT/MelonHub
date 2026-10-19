package com.melonhub.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** 基于 JDK 内置 HttpServer 的平台桩，按 (方法 路径) 注册应答。 */
class StubServer implements AutoCloseable {

    record Response(int status, String body, Map<String, String> headers) {
        static Response json(int status, String body) {
            return new Response(status, body, Map.of());
        }
    }

    private final HttpServer server;
    private final Map<String, BiFunction<HttpExchange, String, Response>> handlers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::dispatch);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void on(String method, String path, BiFunction<HttpExchange, String, Response> handler) {
        handlers.put(method + " " + path, handler);
    }

    int hits(String method, String path) {
        return hits.getOrDefault(method + " " + path, new AtomicInteger()).get();
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        hits.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        BiFunction<HttpExchange, String, Response> handler = handlers.get(key);
        Response response = handler != null ? handler.apply(exchange, body) : Response.json(404, "{\"code\":\"NOT_FOUND\",\"message\":\"stub miss\"}");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        response.headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
