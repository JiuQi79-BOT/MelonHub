package com.melonhub.rbac.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Redis read-through cache for authorization decisions. MySQL stays the source of truth.
 *
 * <p>Entries live under random generation tokens of the user, application, application keys and isolation
 * domain, plus a global epoch. A write that changes authorization state replaces the generation and raises a
 * per-generation "busy" counter before its DB commit, replaces the generation again after commit and lowers
 * the counter on completion. Readers read generations atomically with the busy flag before touching MySQL and
 * never store while busy, so a snapshot taken before a commit is never served after it.
 *
 * <p>If Redis cannot be marked before commit, the write still proceeds; this instance then bypasses Redis
 * until it has advanced the global epoch after that transaction finished. Other instances that kept their
 * connection can serve an older entry until then (retried every two seconds), never longer than
 * {@link #ENTRY_TTL_SECONDS}.
 */
@Component
public class PermissionCache {
    public enum Status { HIT, MISS, NONE, BYPASS }
    /** HIT: meta + payload; MISS: meta + storeKey (null while busy); NONE: unknown id; BYPASS: cache unusable. */
    public record Lookup(Status status, String meta, String payload, String storeKey) {
        static final Lookup BYPASS_LOOKUP = new Lookup(Status.BYPASS, null, null, null);
        static final Lookup NONE_LOOKUP = new Lookup(Status.NONE, null, null, null);
    }

    public static final long ENTRY_TTL_SECONDS = 60;
    private static final long META_TTL_SECONDS = 86_400;
    private static final long BREAKER_MILLIS = 5_000;
    private static final Logger log = LoggerFactory.getLogger(PermissionCache.class);

    // Keys are derived inside the scripts, so this requires a single (non-cluster) Redis node.
    private static final String GENERATIONS = """
        local p = ARGV[1]
        local epoch = redis.call('GET', p .. 'epoch') or '0'
        local function gen(k, fresh)
          local g = redis.call('GET', k)
          if not g then g = fresh; redis.call('SET', k, g, 'EX', 86400) end
          local b = redis.call('GET', k .. ':busy')
          return g, (b and tonumber(b) > 0)
        end
        """;
    private static final DefaultRedisScript<List> CONTEXT_LOOKUP = new DefaultRedisScript<>(GENERATIONS + """
        local meta = redis.call('GET', p .. 'ctx:' .. ARGV[2])
        if not meta then return {'NONE'} end
        local m = cjson.decode(meta)
        local ug, ub = gen(p .. 'gen:u:' .. m.u, ARGV[3])
        local ag, ab = gen(p .. 'gen:a:' .. m.a, ARGV[4])
        local dg, db = gen(p .. 'gen:d:' .. m.d .. ':' .. m.a, ARGV[5])
        if ub or ab or db then return {'MISS', meta, ''} end
        local key = p .. 'eff:' .. ARGV[2] .. ':' .. epoch .. ':' .. ug .. ':' .. ag .. ':' .. dg
        local e = redis.call('GET', key)
        if e then return {'HIT', meta, e} end
        return {'MISS', meta, key}
        """, List.class);
    private static final DefaultRedisScript<List> KEY_LOOKUP = new DefaultRedisScript<>(GENERATIONS + """
        local meta = redis.call('GET', p .. 'key:' .. ARGV[2])
        if not meta then return {'NONE'} end
        local m = cjson.decode(meta)
        local kg, kb = gen(p .. 'gen:k:' .. m.a, ARGV[3])
        if kb then return {'MISS', meta, ''} end
        local key = p .. 'kst:' .. ARGV[2] .. ':' .. epoch .. ':' .. kg
        local s = redis.call('GET', key)
        if s then return {'HIT', meta, s} end
        return {'MISS', meta, key}
        """, List.class);
    private static final DefaultRedisScript<Long> TOUCH = new DefaultRedisScript<>("""
        redis.call('SET', ARGV[1], ARGV[2], 'EX', 86400)
        redis.call('INCR', ARGV[1] .. ':busy')
        redis.call('EXPIRE', ARGV[1] .. ':busy', 120)
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        local v = redis.call('DECR', ARGV[1] .. ':busy')
        if v <= 0 then redis.call('DEL', ARGV[1] .. ':busy') end
        return v
        """, Long.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final String prefix;
    private final MeterRegistry metrics;
    private final Map<String,Counter> counters = new ConcurrentHashMap<>();
    private final AtomicLong missedInvalidations = new AtomicLong();
    private final AtomicLong flushedInvalidations = new AtomicLong();
    private final AtomicInteger unmarkedWrites = new AtomicInteger();
    private volatile long openUntil;
    private volatile long lastWarning;

    public PermissionCache(StringRedisTemplate redis, MeterRegistry metrics,
                           @Value("${melonhub.cache.enabled:true}") boolean enabled,
                           @Value("${melonhub.cache.prefix:melonhub:}") String prefix) {
        this.redis = redis; this.metrics = metrics; this.enabled = enabled; this.prefix = prefix;
    }

    public boolean enabled() { return enabled; }

    public String health() {
        if (!enabled) return "DISABLED";
        try {
            String pong = redis.execute(connection -> connection.ping(), true);
            if (!"PONG".equalsIgnoreCase(pong)) return "DOWN";
            return ready() ? "UP" : "RECOVERING";
        } catch (RuntimeException e) { failed(e); return "DOWN"; }
    }

    public Lookup context(String hash) { return lookup(CONTEXT_LOOKUP, "context", hash, fresh(), fresh(), fresh()); }
    public Lookup applicationKey(String hash) { return lookup(KEY_LOOKUP, "apikey", hash, fresh()); }

    public void putContext(String hash, String meta, long ttlSeconds) { put(prefix + "ctx:" + hash, meta, ttlSeconds); }
    public void putContextAfterCommit(String hash, String meta, long ttlSeconds) {
        if (!enabled) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { putContext(hash, meta, ttlSeconds); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { putContext(hash, meta, ttlSeconds); }
        });
    }
    public void putApplicationKey(String hash, String meta) { put(prefix + "key:" + hash, meta, META_TTL_SECONDS); }
    public void store(String key, String value, long ttlSeconds) { if (key != null && !key.isEmpty()) put(key, value, ttlSeconds); }

    public void forgetContextAfterCommit(String hash) {
        if (!enabled || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { delete(prefix + "ctx:" + hash); }
        });
    }

    public void touchUser(long user) { touch("gen:u:" + user); }
    public void touchApplication(long application) { touch("gen:a:" + application); touch("gen:k:" + application); }
    public void touchKeys(long application) { touch("gen:k:" + application); }
    public void touchDomain(long department, long application) { touch("gen:d:" + department + ":" + application); }

    private void touch(String suffix) {
        if (!enabled) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("Cache invalidation requires an active transaction");
        String key = prefix + suffix;
        if (!touchedInTransaction().add(key)) return;
        boolean marked = false;
        if (System.currentTimeMillis() >= openUntil) {
            try { redis.execute(TOUCH, List.of(), key, fresh()); marked = true; }
            catch (RuntimeException e) { failed(e); }
        }
        if (!marked) {
            missedInvalidations.incrementAndGet();
            unmarkedWrites.incrementAndGet();
        }
        final boolean release = marked;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                if (System.currentTimeMillis() < openUntil) { missedInvalidations.incrementAndGet(); return; }
                try { redis.opsForValue().set(key, fresh(), Duration.ofSeconds(META_TTL_SECONDS)); }
                catch (RuntimeException e) { failed(e); missedInvalidations.incrementAndGet(); }
            }
            @Override public void afterCompletion(int status) {
                if (!release) { unmarkedWrites.decrementAndGet(); return; }
                try { redis.execute(RELEASE, List.of(), key); } catch (RuntimeException e) { failed(e); }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Set<String> touchedInTransaction() {
        var touched = (Set<String>) TransactionSynchronizationManager.getResource(this);
        if (touched != null) return touched;
        Set<String> created = new HashSet<>();
        TransactionSynchronizationManager.bindResource(this, created);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { TransactionSynchronizationManager.unbindResourceIfPossible(PermissionCache.this); }
        });
        return created;
    }

    /**
     * Other instances keep serving entries this instance could not invalidate until the epoch moves, and an idle
     * instance would otherwise only move it on its next lookup.
     */
    @Scheduled(initialDelay = 2_000, fixedDelay = 2_000)
    public void recover() {
        if (usable() && missedInvalidations.get() != flushedInvalidations.get()) ready();
    }

    /** True when this instance has not skipped any invalidation since its last epoch flush. */
    private boolean ready() {
        long missed = missedInvalidations.get();
        if (missed == flushedInvalidations.get()) return true;
        if (unmarkedWrites.get() > 0) return false;
        try {
            redis.opsForValue().increment(prefix + "epoch");
            flushedInvalidations.accumulateAndGet(missed, Math::max);
            log.info("Permission cache epoch advanced after missed invalidations");
            return missed == missedInvalidations.get();
        } catch (RuntimeException e) { failed(e); return false; }
    }

    @SuppressWarnings("unchecked")
    private Lookup lookup(DefaultRedisScript<List> script, String kind, String hash, String... fresh) {
        if (!usable() || !ready()) return count(kind, Lookup.BYPASS_LOOKUP);
        try {
            Object[] args = new Object[fresh.length + 2];
            args[0] = prefix; args[1] = hash;
            System.arraycopy(fresh, 0, args, 2, fresh.length);
            List<Object> result = (List<Object>) redis.execute(script, List.of(), args);
            if (result == null || result.isEmpty() || "NONE".equals(result.get(0))) return count(kind, Lookup.NONE_LOOKUP);
            String meta = (String) result.get(1);
            if ("HIT".equals(result.get(0))) return count(kind, new Lookup(Status.HIT, meta, (String) result.get(2), null));
            String key = (String) result.get(2);
            return count(kind, new Lookup(Status.MISS, meta, null, key == null || key.isEmpty() ? null : key));
        } catch (RuntimeException e) {
            failed(e);
            return count(kind, Lookup.BYPASS_LOOKUP);
        }
    }

    private Lookup count(String kind, Lookup lookup) {
        String result = lookup.status().name().toLowerCase(Locale.ROOT);
        counters.computeIfAbsent(kind + ':' + result,
                k -> Counter.builder("melonhub.cache.lookups").tag("kind", kind).tag("result", result).register(metrics)).increment();
        return lookup;
    }

    private void put(String key, String value, long ttlSeconds) {
        if (!usable() || ttlSeconds <= 0) return;
        try { redis.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds)); } catch (RuntimeException e) { failed(e); }
    }

    private void delete(String key) {
        if (!usable()) return;
        try { redis.delete(key); } catch (RuntimeException e) { failed(e); }
    }

    private boolean usable() { return enabled && System.currentTimeMillis() >= openUntil; }

    private void failed(RuntimeException e) {
        long now = System.currentTimeMillis();
        openUntil = now + BREAKER_MILLIS;
        if (now - lastWarning > 30_000) {
            lastWarning = now;
            log.warn("Redis cache unavailable, falling back to MySQL for {} ms: {}", BREAKER_MILLIS, e.getClass().getSimpleName());
        }
    }

    private static String fresh() { return Long.toHexString(ThreadLocalRandom.current().nextLong()) + Long.toHexString(System.nanoTime()); }
}
