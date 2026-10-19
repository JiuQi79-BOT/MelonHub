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
 * 授权门面：按 (externalId, departmentId) 缓存上下文 token。
 * <p>普通校验（{@link #hasPermission}、{@link #token}）只在用户的当前部门内创建上下文，绝不切换部门：
 * 用户当前部门不同时抛 {@link DepartmentMismatchException}。只有 {@link #switchDepartment} 会切换部门。</p>
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
     * 判断用户在某部门是否拥有权限；传输层故障 fail-closed 返回 false。
     * 上下文失效时在同一部门重建一次；用户已切到其他部门时抛 {@link DepartmentMismatchException}，不会把部门切回来。
     */
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

    /** 取缓存上下文，缺失或临近过期时在用户当前部门内新建；不会切换部门。 */
    public String token(String externalId, long departmentId) {
        Key key = new Key(externalId, departmentId);
        CachedContext cached = contexts.get(key);
        if (cached != null && cached.expiresAtSeconds > Instant.now().getEpochSecond() + EXPIRY_SKEW_SECONDS) {
            return cached.token;
        }
        return remember(key, client.issueContext(externalId, departmentId, null, false));
    }

    /**
     * 用户主动切换到另一个部门时调用：创建该部门的上下文并把它设为用户的当前部门。
     * 平台会使该用户在其他部门所有软件中的上下文失效，本地缓存同步清理。
     */
    public String switchDepartment(String externalId, long departmentId) {
        ContextView view = client.issueContext(externalId, departmentId, null, true);
        contexts.keySet().removeIf(k -> k.externalId.equals(externalId));
        return remember(new Key(externalId, departmentId), view);
    }

    public void evict(String externalId, long departmentId) {
        contexts.remove(new Key(externalId, departmentId));
    }

    public void evictAll() {
        contexts.clear();
    }

    /**
     * 处理 Webhook 事件：data 为事件 payload 的 data 对象，按其中的 externalId/departmentId 清缓存。
     * 只有 departmentId 时清理该部门全部用户；两者都缺失时清理此客户端的全部缓存。
     */
    public void handleWebhookEvent(String type, JsonNode data) {
        if (!EVICT_EVENTS.contains(type) || data == null) {
            return;
        }
        JsonNode externalIdNode = data.get("externalId");
        String externalId = externalIdNode == null ? null : externalIdNode.asText();
        JsonNode deptNode = data.get("departmentId");
        contexts.keySet().removeIf(k -> (externalId == null || k.externalId.equals(externalId))
                && (deptNode == null || k.departmentId == deptNode.asLong()));
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
