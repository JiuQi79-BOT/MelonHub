package com.melonhub.rbac.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.cache.PermissionCache;
import com.melonhub.rbac.dao.ContextDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.Credentials;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issues and validates permission contexts. Validation never locks rows: it reads one consistent snapshot,
 * and repeated checks are served from {@link PermissionCache} until an authorization change invalidates them.
 */
@Service
public class ContextService {
    public static final long CONTEXT_SECONDS = 3600;

    record Meta(String u, String a, long v, long exp) {
        long user() { return Long.parseLong(u); }
        long application() { return Long.parseLong(a); }
        boolean usableFor(long app) { return application() == app && exp > Instant.now().getEpochSecond(); }
    }
    record Role(long id, String name, String code) {}
    /** refreshAt: epoch second when the earliest included assignment lapses; the result must not be served after it. */
    record Payload(boolean valid, List<Role> roles, List<String> permissions, Long refreshAt) {
        static final Payload INVALID = new Payload(false, List.of(), List.of(), null);
        boolean lapsed() { return refreshAt != null && refreshAt <= Instant.now().getEpochSecond(); }
    }
    record Access(Meta meta, Payload payload) {}

    private final ContextDao dao;
    private final RbacService rbac;
    private final PermissionCache cache;
    private final ObjectMapper json;
    private final TransactionTemplate snapshot;

    public ContextService(ContextDao dao, RbacService rbac, PermissionCache cache, ObjectMapper json, PlatformTransactionManager transactions) {
        this.dao = dao; this.rbac = rbac; this.cache = cache; this.json = json;
        this.snapshot = new TransactionTemplate(transactions);
    }

    @Transactional public Map<String,Object> issue(long app, Requests.Context input, String actor) {
        rbac.domain(app);
        var found = dao.lockUserByExternalId(input.externalId());
        if (found.isEmpty()) throw ApiException.missing("用户不存在");
        var user = found.get(0);
        long userId = ((Number) user.get("id")).longValue();
        if (!Boolean.TRUE.equals(user.get("enabled"))) throw ApiException.forbidden("用户已停用");
        if (!dao.applicationEnabled(app)) throw ApiException.forbidden("软件已停用");
        var assigned = dao.assignedRoles(userId, app);
        List<Long> active = input.activeRoleIds() == null ? assigned : new ArrayList<>(new LinkedHashSet<>(input.activeRoleIds()));
        if (!new HashSet<>(assigned).containsAll(active)) throw ApiException.forbidden("激活角色必须为用户在当前软件内已分配的角色");
        long version = ((Number) user.get("context_version")).longValue();
        dao.clearStaleContexts(userId, version);
        String token = Credentials.token();
        String hash = Credentials.hash(token);
        // MySQL TIMESTAMP rounds fractions; whole seconds keep the stored and cached expiry identical.
        Instant expires = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(CONTEXT_SECONDS);
        dao.create(hash, userId, app, version, expires);
        dao.activateRoles(hash, active);
        rbac.audit(actor, "CONTEXT_ISSUED", input.externalId(), null, app,
                "激活角色 " + active.size() + " 个");
        var meta = new Meta(String.valueOf(userId), String.valueOf(app), version, expires.getEpochSecond());
        var access = new Access(meta, compute(hash, meta));
        cache.putContextAfterCommit(hash, write(meta), CONTEXT_SECONDS);
        var result = new LinkedHashMap<String,Object>();
        result.put("contextToken", token);
        result.putAll(view(access));
        return result;
    }

    public Map<String,Object> effective(long app, String token) { return view(resolve(app, token)); }

    public Map<String,Object> check(long app, String token, List<String> requested) {
        var access = resolve(app, token);
        var granted = new HashSet<>(access.payload().permissions());
        Map<String,Boolean> decisions = new LinkedHashMap<>();
        for (String permission : requested) decisions.put(permission, granted.contains(permission));
        var result = new LinkedHashMap<String,Object>();
        result.put("allowed", decisions.values().stream().allMatch(Boolean::booleanValue));
        result.put("decisions", decisions);
        result.put("contextVersion", access.meta().v());
        return result;
    }

    @Transactional public void revoke(long app, String token, String actor) {
        var access = resolve(app, token);
        String hash = Credentials.hash(token);
        dao.revoke(hash, app);
        cache.touchUser(access.meta().user());
        cache.forgetContextAfterCommit(hash);
        rbac.audit(actor, "CONTEXT_REVOKED", "user:" + access.meta().u(), null, app, "");
    }

    private Access resolve(long app, String token) {
        String hash = Credentials.hashOrNull(token);
        if (hash == null) throw ApiException.contextInvalid();
        var lookup = cache.context(hash);
        Meta cachedMeta = lookup.meta() == null ? null : read(lookup.meta(), Meta.class);
        if (lookup.status() == PermissionCache.Status.HIT && cachedMeta != null) {
            Payload payload = read(lookup.payload(), Payload.class);
            if (payload != null && !payload.lapsed()) {
                if (!cachedMeta.usableFor(app) || !payload.valid()) throw ApiException.contextInvalid();
                return new Access(cachedMeta, payload);
            }
        }
        if (cachedMeta != null && !cachedMeta.usableFor(app)) throw ApiException.contextInvalid();
        Access loaded = snapshot.execute(status -> load(hash));
        String storeKey = lookup.status() == PermissionCache.Status.MISS ? lookup.storeKey() : null;
        if (loaded == null) {
            cache.store(storeKey, write(Payload.INVALID), PermissionCache.ENTRY_TTL_SECONDS);
            throw ApiException.contextInvalid();
        }
        long now = Instant.now().getEpochSecond();
        long remaining = loaded.meta().exp() - now;
        if (lookup.status() == PermissionCache.Status.NONE) cache.putContext(hash, write(loaded.meta()), remaining);
        long ttl = Math.min(PermissionCache.ENTRY_TTL_SECONDS, remaining);
        // Expiry is not a write, so nothing touches the cache when an assignment lapses; the entry has to end by itself.
        if (loaded.payload().refreshAt() != null) ttl = Math.min(ttl, loaded.payload().refreshAt() - now);
        cache.store(storeKey, write(loaded.payload()), ttl);
        if (!loaded.meta().usableFor(app) || !loaded.payload().valid()) throw ApiException.contextInvalid();
        return loaded;
    }

    private Access load(String hash) {
        var found = dao.state(hash);
        if (found.isEmpty()) return null;
        var row = found.get().context();
        var meta = new Meta(String.valueOf(row.user()), String.valueOf(row.application()), row.version(), row.expiresAt().getEpochSecond());
        return new Access(meta, found.get().valid() ? compute(hash, meta) : Payload.INVALID);
    }

    private Payload compute(String hash, Meta meta) {
        var roles = new ArrayList<Role>();
        Long refreshAt = null;
        for (var r : dao.effectiveRoles(meta.user(), hash, meta.application())) {
            roles.add(new Role(((Number) r.get("id")).longValue(), (String) r.get("name"), (String) r.get("code")));
            if (r.get("expires_at") instanceof Timestamp expires) {
                long at = expires.toInstant().getEpochSecond();
                if (refreshAt == null || at < refreshAt) refreshAt = at;
            }
        }
        return new Payload(true, roles, dao.effectivePermissions(meta.user(), hash, meta.application()), refreshAt);
    }

    private static Map<String,Object> view(Access access) {
        var meta = access.meta();
        var result = new LinkedHashMap<String,Object>();
        result.put("userId", meta.user());
        result.put("applicationId", meta.application());
        result.put("contextVersion", meta.v());
        result.put("expiresAt", Instant.ofEpochSecond(meta.exp()));
        result.put("roles", access.payload().roles());
        result.put("permissions", access.payload().permissions());
        return result;
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null) return null;
        try { return json.readValue(value, type); } catch (JsonProcessingException | RuntimeException e) { return null; }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
