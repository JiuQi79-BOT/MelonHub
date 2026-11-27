package com.melonhub.rbac.service.ddd.domain.value;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色分配有效期值对象与三层优先级解析规则：
 * 显式逐角色期限 {@code expiries}（{@code expiresAt == null} 表示永久）&gt; 批量有效天数 {@code expiresInDays}
 * &gt; 保留已有有效分配原期限（新增角色默认永久）。
 */
public final class GrantDuration {
    public static final int MAX_EXPIRY_DAYS = 3650;

    private GrantDuration() {}

    public record RoleExpiryOverride(long roleId, Instant expiresAt) {}

    public static Instant fromDays(Integer expiresInDays, Instant now) {
        if (expiresInDays == null) return null;
        return now.truncatedTo(ChronoUnit.SECONDS).plus(expiresInDays, ChronoUnit.DAYS);
    }

    public static Instant validateExplicitExpiry(Instant rawExpiry, Instant now) {
        if (rawExpiry == null) return null;
        Instant base = now.truncatedTo(ChronoUnit.SECONDS);
        Instant truncated = rawExpiry.truncatedTo(ChronoUnit.SECONDS);
        if (!truncated.isAfter(base)) {
            throw DomainException.bad("到期时间必须晚于当前时间");
        }
        if (truncated.isAfter(base.plus(MAX_EXPIRY_DAYS, ChronoUnit.DAYS))) {
            throw DomainException.bad("到期时间不能晚于 3650 天后");
        }
        return truncated;
    }

    public static Map<Long, Instant> resolveExplicitMap(
            Collection<Long> targetRoleIds,
            List<RoleExpiryOverride> overrides,
            Instant now
    ) {
        var explicit = new HashMap<Long, Instant>();
        if (overrides == null) return explicit;
        for (var entry : overrides) {
            if (!targetRoleIds.contains(entry.roleId())) {
                throw DomainException.bad("只能为本次分配的角色设置到期时间");
            }
            explicit.put(entry.roleId(), validateExplicitExpiry(entry.expiresAt(), now));
        }
        return explicit;
    }
}
