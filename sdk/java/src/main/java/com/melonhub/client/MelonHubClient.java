package com.melonhub.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.client.dto.AccessRequestView;
import com.melonhub.client.dto.CheckResult;
import com.melonhub.client.dto.ContextView;
import com.melonhub.client.dto.UserAccessView;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MelonHub 统一权限平台业务 API 客户端（线程安全）。
 * <p>失败语义：429/503 按 Retry-After 秒数退避重试（默认最多 2 次）；其余 4xx/5xx 抛
 * {@link MelonHubApiException}，401 且 code=CONTEXT_INVALID 抛子类 {@link ContextInvalidException}，
 * 409 且 code=DEPARTMENT_MISMATCH 抛子类 {@link DepartmentMismatchException}；
 * 网络/超时异常包装为 {@link MelonHubException}。</p>
 */
public class MelonHubClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_RETRIES = 2;

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final ObjectMapper mapper = new ObjectMapper();

    public MelonHubClient(String baseUrl, String apiKey) {
        this(builder(baseUrl, apiKey));
    }

    private MelonHubClient(Builder builder) {
        this.baseUrl = stripTrailingSlash(builder.baseUrl);
        this.apiKey = builder.apiKey;
        this.requestTimeout = builder.requestTimeout;
        this.maxRetries = builder.maxRetries;
        this.http = HttpClient.newBuilder().connectTimeout(builder.connectTimeout).build();
    }

    public static Builder builder(String baseUrl, String apiKey) {
        return new Builder(baseUrl, apiKey);
    }

    /**
     * 创建上下文；activeRoleIds 传 null 表示激活全部已分配启用角色，空列表表示无激活角色。
     * 与平台默认行为一致：部门与用户当前部门不同时会切换部门。
     */
    public ContextView issueContext(String externalId, long departmentId, List<Long> activeRoleIds) {
        return issueContext(externalId, departmentId, activeRoleIds, true);
    }

    /**
     * 创建上下文。switchDepartment=false 时只在用户当前部门（或尚无当前部门时）创建，
     * 当前部门不同则抛 {@link DepartmentMismatchException}，不会让其他部门的上下文失效。
     */
    public ContextView issueContext(String externalId, long departmentId, List<Long> activeRoleIds, boolean switchDepartment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalId", externalId);
        body.put("departmentId", departmentId);
        if (activeRoleIds != null) {
            body.put("activeRoleIds", activeRoleIds);
        }
        body.put("switchDepartment", switchDepartment);
        return send("POST", "/api/v1/contexts", null, body, ContextView.class);
    }

    /** 查询上下文当前视图；失效/过期/跨软件时抛 {@link ContextInvalidException}。 */
    public ContextView effective(String contextToken) {
        return send("GET", "/api/v1/contexts/effective", contextToken, null, ContextView.class);
    }

    /** 逐项权限校验。 */
    public CheckResult check(String contextToken, List<String> permissions) {
        Map<String, Object> body = Map.of("permissions", permissions);
        JsonNode node = send("POST", "/api/v1/contexts/check", contextToken, body, JsonNode.class);
        Map<String, Boolean> decisions = new HashMap<>();
        JsonNode d = node.path("decisions");
        d.properties().forEach(e -> decisions.put(e.getKey(), e.getValue().asBoolean()));
        return new CheckResult(node.path("allowed").asBoolean(), decisions, node.path("contextVersion").asLong());
    }

    /** 撤销上下文。 */
    public void revoke(String contextToken) {
        send("DELETE", "/api/v1/contexts", contextToken, null, JsonNode.class);
    }

    /** 只读查询用户在某部门的授权，不创建上下文。 */
    public UserAccessView userAccess(String externalId, long departmentId) {
        String query = "?externalId=" + encode(externalId) + "&departmentId=" + departmentId;
        return send("GET", "/api/v1/users/access" + query, null, null, UserAccessView.class);
    }

    /** 提交角色申请；durationDays 传 null 表示长期。 */
    public AccessRequestView submitAccessRequest(String externalId, long departmentId, long roleId,
                                                 String reason, Integer durationDays) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalId", externalId);
        body.put("departmentId", departmentId);
        body.put("roleId", roleId);
        body.put("reason", reason);
        if (durationDays != null) {
            body.put("durationDays", durationDays);
        }
        return send("POST", "/api/v1/access-requests", null, body, AccessRequestView.class);
    }

    /** 查询该用户在本软件的申请列表。 */
    public List<AccessRequestView> myAccessRequests(String externalId) {
        JsonNode node = send("GET", "/api/v1/access-requests?externalId=" + encode(externalId), null, null, JsonNode.class);
        try {
            return mapper.readerForListOf(AccessRequestView.class).readValue(node);
        } catch (IOException e) {
            throw new MelonHubException("解析申请列表失败", e);
        }
    }

    /** 撤销自己的 PENDING 申请。 */
    public void cancelAccessRequest(long id, String externalId) {
        send("POST", "/api/v1/access-requests/" + id + "/cancel", null, Map.of("externalId", externalId), JsonNode.class);
    }

    private <T> T send(String method, String path, String contextToken, Object body, Class<T> type) {
        HttpResponse<String> response = executeWithRetry(method, path, contextToken, body);
        try {
            return mapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new MelonHubException("解析响应失败: " + path, e);
        }
    }

    private HttpResponse<String> executeWithRetry(String method, String path, String contextToken, Object body) {
        int attempt = 0;
        while (true) {
            HttpResponse<String> response = executeOnce(method, path, contextToken, body);
            int status = response.statusCode();
            if ((status == 429 || status == 503) && attempt < maxRetries) {
                sleep(retryAfterMillis(response));
                attempt++;
                continue;
            }
            if (status >= 400) {
                throw toException(status, response.body());
            }
            return response;
        }
    }

    private HttpResponse<String> executeOnce(String method, String path, String contextToken, Object body) {
        try {
            String json = body == null ? null : mapper.writeValueAsString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("X-API-Key", apiKey);
            if (contextToken != null) {
                builder.header("X-Context-Token", contextToken);
            }
            switch (method) {
                case "GET" -> builder.GET();
                case "DELETE" -> builder.DELETE();
                case "POST" -> builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json, StandardCharsets.UTF_8));
                default -> throw new IllegalArgumentException("不支持的方法: " + method);
            }
            var response = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            try {
                return response.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                response.cancel(true);
            }
        } catch (TimeoutException e) {
            throw new MelonHubException("请求超时: " + method + " " + path, e);
        } catch (ExecutionException e) {
            throw new MelonHubException("请求失败: " + method + " " + path, e.getCause());
        } catch (IOException e) {
            throw new MelonHubException("请求失败: " + method + " " + path + " - " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MelonHubException("请求被中断: " + method + " " + path, e);
        }
    }

    private MelonHubApiException toException(int status, String responseBody) {
        String code = null;
        String message = null;
        String requestId = null;
        try {
            JsonNode node = mapper.readTree(responseBody);
            code = textOrNull(node, "code");
            message = textOrNull(node, "message");
            requestId = textOrNull(node, "requestId");
        } catch (IOException ignored) {
            // 非 JSON 错误体，保留原文作为 message
        }
        if (message == null) {
            message = responseBody == null || responseBody.isBlank() ? "HTTP " + status : responseBody;
        }
        if (status == 401 && "CONTEXT_INVALID".equals(code)) {
            return new ContextInvalidException(code, message, requestId);
        }
        if (status == 409 && "DEPARTMENT_MISMATCH".equals(code)) {
            return new DepartmentMismatchException(code, message, requestId);
        }
        return new MelonHubApiException(status, code, message, requestId);
    }

    private static long retryAfterMillis(HttpResponse<String> response) {
        String header = response.headers().firstValue("Retry-After").orElse(null);
        if (header != null) {
            try {
                return Math.max(0, Long.parseLong(header.trim())) * 1000L;
            } catch (NumberFormatException ignored) {
                // Retry-After 只约定秒数，无法解析时按 1 秒退避
            }
        }
        return 1000L;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MelonHubException("退避等待被中断", e);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 全参构建器。 */
    public static final class Builder {
        private final String baseUrl;
        private final String apiKey;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private int maxRetries = DEFAULT_MAX_RETRIES;

        private Builder(String baseUrl, String apiKey) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public MelonHubClient build() {
            return new MelonHubClient(this);
        }
    }
}
