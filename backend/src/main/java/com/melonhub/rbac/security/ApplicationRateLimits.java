package com.melonhub.rbac.security;

import com.melonhub.rbac.dao.RbacDao;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Per-application rate limits cached briefly; a changed limit takes effect within half a minute per instance. */
@Component
public class ApplicationRateLimits {
    private static final long TTL_MILLIS = 30_000;
    private record Entry(int limit, long loadedAt) {}

    private final RbacDao dao;
    private final int defaultLimit;
    private final Map<Long,Entry> limits = new ConcurrentHashMap<>();

    public ApplicationRateLimits(RbacDao dao, @Value("${melonhub.rate-limit.application-per-second:1000}") int defaultLimit) {
        this.dao = dao;
        this.defaultLimit = defaultLimit;
    }

    public int limit(long application) {
        var entry = limits.get(application);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.loadedAt() < TTL_MILLIS) return entry.limit();
        int limit = dao.applicationRateLimit(application, defaultLimit);
        limits.put(application, new Entry(limit, now));
        return limit;
    }
}
