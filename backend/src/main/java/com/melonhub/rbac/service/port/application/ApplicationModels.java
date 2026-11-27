package com.melonhub.rbac.service.port.application;

import java.time.Instant;

/**
 * 业务软件、API Key 与软件限额模块的应用层命令与结果模型。
 */
public final class ApplicationModels {
    private ApplicationModels() {}

    public record SaveApplicationCommand(String name, String code, String description, String owner) {
        public SaveApplicationCommand(String name, String code, String description) {
            this(name, code, description, null);
        }
    }

    public record CreateApiKeyCommand(String name, Integer expiresInDays) {}

    public record ApplicationResult(
            long id,
            String name,
            String code,
            String description,
            String owner,
            boolean enabled,
            Integer rateLimit,
            Instant createdAt,
            long roleCount,
            long permissionCount,
            long activeKeyCount
    ) {}

    public record ApplicationCreatedResult(long id, String apiKey, long keyId) {}

    public record ApiKeyResult(
            long id,
            String name,
            String prefix,
            String createdBy,
            Instant createdAt,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant revokedAt,
            String status
    ) {}

    public record ApiKeyCreatedResult(long id, String name, String prefix, String apiKey, Instant expiresAt) {}
}
