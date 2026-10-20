package com.melonhub.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.melonhub.client.dto.ContextView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 授权门面：按 externalId 缓存当前软件的上下文 token。
 * <p>一个客户端绑定一个软件的 API Key；不同软件使用各自的客户端。</p>
 * <p>fail-closed：平台 5xx、网络异常等传输层故障时 {@link #hasPermission} 返回 false 并记 warn；
 * 业务错误（4xx）以及 {@link ContextInvalidException} 重建后仍失败时向外抛出。</p>
 */
public class MelonHubAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(MelonHubAuthorizer.class);

    /** 距过期不足该秒数即视为过期，避免边界竞态 */
    private static final long EXPIRY_SKEW_SECONDS = 60;

    /** 会影响该用户授权结果、需清缓存的 Webhook 事件 */
    private static final Set<String> EVICT_EVENTS = Set.of(
            "user.enabled", "user.disabled", "user.deleted", "user.updated", "user.roles.changed", "user.roles.expired", "user.contexts.revoked",
            "access.approved", "access.rejected", "review.closed",
            "role.enabled", "role.disabled", "role.deleted", "role.permissions.changed", "permission.updated", "permission.deleted",
            "application.enabled", "application.disabled", "apikey.revoked");

    private final MelonHubClient client;
    private final Map<String, CachedContext> contexts = new ConcurrentHashMap<>();

    public MelonHubAuthorizer(MelonHubClient client) {
        this.client = client;
    }

    /** 判断用户在本软件是否拥有权限；失效上下文重建一次，传输层故障 fail-closed 返回 false。 */
    public boolean hasPermission(String externalId, String permission) {
        try {
            try {
                return client.check(token(externalId), List.of(permission)).allowed();
            } catch (ContextInvalidException e) {
                evict(externalId);
                return client.check(token(externalId), List.of(permission)).allowed();
            }
        } catch (MelonHubApiException e) {
            if (e.getStatus() >= 500) {
                log.warn("MelonHub 平台错误 {}，fail-closed 拒绝: user={} perm={}", e.getStatus(), externalId, permission);
                return false;
            }
            throw e;
        } catch (MelonHubException e) {
            log.warn("MelonHub 平台不可达，fail-closed 拒绝: user={} perm={} - {}", externalId, permission, e.getMessage());
            return false;
        }
    }

    /** 取当前软件的缓存上下文，缺失或临近过期时新建。 */
    public String token(String externalId) {
        CachedContext cached = contexts.get(externalId);
        if (cached != null && cached.expiresAtSeconds > Instant.now().getEpochSecond() + EXPIRY_SKEW_SECONDS) {
            return cached.token;
        }
        return remember(externalId, client.issueContext(externalId, null));
    }

    public void evict(String externalId) {
        contexts.remove(externalId);
    }

    public void evictAll() {
        contexts.clear();
    }

    /** 处理本软件的 Webhook：按 externalId 清缓存；未指定用户时清理此客户端的全部上下文。 */
    public void handleWebhookEvent(String type, JsonNode data) {
        if (!EVICT_EVENTS.contains(type) || data == null) {
            return;
        }
        JsonNode externalIdNode = data.get("externalId");
        String externalId = externalIdNode == null ? null : externalIdNode.asText();
        if (externalId == null) evictAll();
        else evict(externalId);
    }

    private String remember(String key, ContextView view) {
        long expiresAt = Instant.parse(view.getExpiresAt()).getEpochSecond();
        contexts.put(key, new CachedContext(view.getContextToken(), expiresAt));
        return view.getContextToken();
    }

    private record CachedContext(String token, long expiresAtSeconds) {
    }
}
