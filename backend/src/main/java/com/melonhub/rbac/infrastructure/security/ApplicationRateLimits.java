package com.melonhub.rbac.infrastructure.security;

import com.melonhub.rbac.service.port.application.ApplicationUseCase;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 业务软件每秒调用限额的实例内短时缓存（30 秒刷新一次）。
 */
@Component
public class ApplicationRateLimits {
    private static final long TTL_MILLIS = 30_000;

    private record Entry(int limit, long loadedAt) {}

    private final ApplicationUseCase applications;
    private final int defaultLimit;
    private final Map<Long, Entry> limits = new ConcurrentHashMap<>();

    public ApplicationRateLimits(ApplicationUseCase applications,
                                 @Value("${melonhub.rate-limit.application-per-second:1000}") int defaultLimit) {
        this.applications = applications;
        this.defaultLimit = defaultLimit;
    }

    public int limit(long application) {
        Entry entry = limits.get(application);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.loadedAt() < TTL_MILLIS) {
            return entry.limit();
        }
        int limit = applications.applicationRateLimit(application, defaultLimit);
        limits.put(application, new Entry(limit, now));
        return limit;
    }
}
