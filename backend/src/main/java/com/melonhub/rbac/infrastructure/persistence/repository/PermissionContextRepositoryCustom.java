package com.melonhub.rbac.infrastructure.persistence.repository;

import java.time.Instant;

/** 权限上下文指定主键插入片段（避免对 CHAR(64) 令牌哈希执行额外查询或覆盖）。 */
public interface PermissionContextRepositoryCustom {
    void persistContext(String hash, long userId, long departmentId, long applicationId,
                        long contextVersion, Instant expiresAt);
}
