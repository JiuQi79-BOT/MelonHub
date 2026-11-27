package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AdminAccount;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** 需要按行锁刷新或操作集合表的管理员账号自定义仓储片段。 */
public interface AdminAccountRepositoryCustom {
    /** 清空持久化上下文并按主键顺序锁定全部管理员账号行，串行化管理树与管理员角色变更。 */
    List<AdminAccount> lockAll();

    long createAccount(String username, String displayName, String roleCode, String passwordHash,
                       boolean enabled, Long parentId, String kind, boolean allDepartments, boolean allApplications);

    void resetBootstrap(String username, String passwordHash, String superAdminRole);

    int updateAccount(long id, String displayName, String roleCode, boolean enabled, String kind,
                      boolean allDepartments, boolean allApplications);

    void moveUnder(long id, long parentId);

    boolean recordFailure(long id, int maxFailures, int lockMinutes);

    int createSessionIfCredentialsMatch(String tokenHash, long accountId, long expectedVersion, String expectedPasswordHash,
                                        String expectedMfaSecret, Instant expiresAt, String sourceIp);

    void replaceDepartmentIds(long accountId, Collection<Long> departmentIds);

    void replaceApplicationIds(long accountId, Collection<Long> applicationIds);
}
