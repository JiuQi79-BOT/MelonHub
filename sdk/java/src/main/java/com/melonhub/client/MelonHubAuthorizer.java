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
 * 授权门面：在当前客户端的软件范围内按用户缓存上下文 token，部门参数仅兼容旧调用。
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
    private final Map<Key, CachedContext> contexts = new ConcurrentHashMap<>();

    public MelonHubAuthorizer(MelonHubClient client) {
        this.client = client;
    }

    /**
     * 判断用户在当前软件是否拥有权限；传输层故障 fail-closed 返回 false。
     */
    public boolean hasPermission(String externalId, String permission) { return hasPermission(externalId, 0, permission); }

    public String token(String externalId) { return token(externalId, 0); }

    public boolean hasPermission(String externalId, long departmentId, String permission) {
        try {
            try {
                return client.check(token(externalId, departmentId), List.of(permission)).allowed();
            } catch (ContextInvalidException e) {
                evict(externalId, departmentId);
                return client.check(token(externalId, departmentId), List.of(permission)).allowed();
            }
        } catch (MelonHubApiException e) {
            if (e.getStatus() >= 500) {
                log.warn("MelonHub 平台错误 {}，fail-closed 拒绝: user={} dept={} perm={}", e.getStatus(), externalId, departmentId, permission);
                return false;
            }
            throw e;
        } catch (MelonHubException e) {
            log.warn("MelonHub 平台不可达，fail-closed 拒绝: user={} dept={} perm={} - {}", externalId, departmentId, permission, e.getMessage());
            return false;
        }
    }

    /** 取当前软件的用户上下文，缺失或临近过期时新建。 */
    public String token(String externalId, long departmentId) {
        Key key = new Key(externalId, 0);
        CachedContext cached = contexts.get(key);
        if (cached != null && cached.expiresAtSeconds > Instant.now().getEpochSecond() + EXPIRY_SKEW_SECONDS) {
            return cached.token;
        }
        return remember(key, client.issueContext(externalId, departmentId, null, false));
    }

    /** 兼容旧调用；部门切换不改变软件授权，也不结束其他上下文。 */
    public String switchDepartment(String externalId, long departmentId) {
        return token(externalId, departmentId);
    }

    public void evict(String externalId, long departmentId) {
        contexts.remove(new Key(externalId, 0));
    }

    public void evictAll() {
        contexts.clear();
    }

    /**
     * 处理 Webhook 事件：按 externalId 清理用户在当前软件的缓存。
     * 没有 externalId 时清理此客户端的软件缓存，departmentId 不限制失效范围。
     */
    public void handleWebhookEvent(String type, JsonNode data) {
        if (!EVICT_EVENTS.contains(type) || data == null) {
            return;
        }
        JsonNode externalIdNode = data.get("externalId");
        String externalId = externalIdNode == null ? null : externalIdNode.asText();
        contexts.keySet().removeIf(k -> externalId == null || k.externalId.equals(externalId));
    }

    private String remember(Key key, ContextView view) {
        long expiresAt = Instant.parse(view.getExpiresAt()).getEpochSecond();
        contexts.put(key, new CachedContext(view.getContextToken(), expiresAt));
        return view.getContextToken();
    }

    private record Key(String externalId, long departmentId) {
    }

    private record CachedContext(String token, long expiresAtSeconds) {
    }
}
