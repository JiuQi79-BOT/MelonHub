package com.melonhub.rbac.service.ddd.domain.repository;

import com.melonhub.rbac.service.ddd.domain.model.AdminCredentialAccount;
import com.melonhub.rbac.service.ddd.domain.model.AdminHierarchy;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 控制台管理员账号、层级树、会话与 API Key 领域仓储端口。
 */
public interface AdminAccountRepositoryPort {
    record SessionSnapshot(long id, String username, String displayName, String role, boolean allApplications,
                           String kind, boolean root, boolean allDepartments, boolean stale) {}

    record ApiKeySnapshot(long keyId, long applicationId, boolean valid, Instant expiresAt) {}

    record AccountSummaryRecord(long id, String username, String displayName, String role, boolean enabled,
                                int failedAttempts, long locked, Instant lockedUntil, Instant lastLoginAt,
                                Instant passwordChangedAt, Instant createdAt, long activeSessions, long mfaEnabled,
                                boolean allApplications, Long parentId, String parentUsername, String kind,
                                boolean allDepartments) {}

    record AccountApplicationPair(long accountId, long applicationId) {}

    record AccountDepartmentPair(long accountId, long departmentId) {}

    boolean accountExists(String username);

    long createAccount(String username, String displayName, String role, String passwordHash, boolean enabled,
                       Long parentId, String kind, boolean allDepartments, boolean allApplications);

    void resetBootstrap(String username, String passwordHash, String superAdminRole);

    Optional<AdminCredentialAccount> findAccountByUsername(String username);

    Optional<AdminCredentialAccount> findAccountById(long id);

    boolean recordFailure(long id, int maxFailures, int lockMinutes);

    void recordSuccess(long id);

    int createSession(String tokenHash, AdminCredentialAccount account, Instant expiresAt, String sourceIp);

    Optional<SessionSnapshot> findActiveSession(String tokenHash, int idleMinutes);

    void touchSession(String tokenHash);

    void deleteSession(String tokenHash);

    void endAllSessions(long accountId);

    void keepOnlySession(long accountId, String tokenHash);

    int replacePassword(long accountId, String expectedHash, String newHash);

    int updatePassword(long accountId, String newHash);

    void setMfaPendingSecret(long accountId, String secret);

    String findMfaPendingSecret(long accountId);

    int confirmMfa(long accountId, String expectedPending, long step);

    int consumeMfaStep(long accountId, String expectedSecret, long step);

    int disableMfaWithStep(long accountId, String expectedSecret, long step);

    int disableMfa(long accountId);

    Optional<ApiKeySnapshot> findApiKeyByHash(String keyHash);

    void markKeysUsed(Collection<Long> keyIds);

    int purgeSessions(int idleMinutes, int limit);

    void lockAllAccounts();

    List<AdminHierarchy.AdminNode> loadHierarchyNodes();

    List<AccountSummaryRecord> listAccountRows();

    List<AccountApplicationPair> accountApplicationPairs();

    List<AccountDepartmentPair> accountDepartmentPairs();

    List<Long> accountApplications(long accountId);

    List<Long> accountDepartments(long accountId);

    long countExistingApplications(Collection<Long> ids);

    long countExistingDepartments(Collection<Long> ids);

    void replaceAccountApplications(long accountId, Collection<Long> applicationIds);

    void replaceAccountDepartments(long accountId, Collection<Long> departmentIds);

    void updateAccount(long id, String displayName, String role, boolean enabled, String kind, boolean allDepartments, boolean allApplications);

    void moveAccountUnder(long id, long parentId);

    int unlockAccount(long id);

    int deleteAccount(long id);
}
