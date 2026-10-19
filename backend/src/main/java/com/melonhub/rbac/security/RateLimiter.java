package com.melonhub.rbac.security;

import java.util.concurrent.ConcurrentHashMap;

/** Per-instance token buckets; protects the shared database from a single noisy caller at peak time. */
public final class RateLimiter {
    private final double ratePerSecond;
    private final double capacity;
    private final ConcurrentHashMap<String,Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(double ratePerSecond, double capacity) { this.ratePerSecond = ratePerSecond; this.capacity = capacity; }

    public boolean tryAcquire(String key) {
        if (ratePerSecond <= 0) return true;
        if (buckets.size() > 50_000) buckets.clear();
        return buckets.computeIfAbsent(key, k -> new Bucket(capacity)).take(ratePerSecond, capacity);
    }

    private static final class Bucket {
        private double tokens;
        private long updatedNanos = System.nanoTime();
        Bucket(double tokens) { this.tokens = tokens; }
        synchronized boolean take(double rate, double capacity) {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - updatedNanos) / 1e9 * rate);
            updatedNanos = now;
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }
    }
}
