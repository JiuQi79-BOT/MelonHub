package com.melonhub.rbac.infrastructure.persistence.repository;

import java.time.Instant;

/** 统一身份凭据幂等保存与加锁建会话片段。 */
public interface IdentityCredentialRepositoryCustom {
    void upsertCredential(long userId, String username, String passwordHash);

    boolean createSessionIfVerified(String tokenHash, long userId, String username, String passwordHash,
                                    String audience, Instant expiresAt);
}
