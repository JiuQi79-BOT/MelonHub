package com.melonhub.rbac.service.port.context;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 在线权限上下文签发、生效查询与批量校验的结果模型。
 */
public final class ContextModels {
    private ContextModels() {}

    public record ContextRoleResult(long id, String name, String code) {}

    public record ContextResult(
            String contextToken,
            long userId,
            long departmentId,
            long applicationId,
            long contextVersion,
            Instant expiresAt,
            List<ContextRoleResult> roles,
            List<String> permissions
    ) {}

    public record CheckResult(boolean allowed, Map<String, Boolean> decisions, long contextVersion) {}
}
