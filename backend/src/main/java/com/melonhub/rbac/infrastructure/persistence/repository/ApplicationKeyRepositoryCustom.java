package com.melonhub.rbac.infrastructure.persistence.repository;

/** 需要加锁读取有效密钥数的自定义仓储片段。 */
public interface ApplicationKeyRepositoryCustom {
    long countLockedActive(long applicationId);
}
