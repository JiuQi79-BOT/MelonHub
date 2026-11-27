package com.melonhub.rbac.infrastructure.persistence.gateway;

import com.melonhub.rbac.infrastructure.persistence.entity.AdminAccount;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminAccountRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminRoleRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.AdminSessionRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationKeyRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.ApplicationRepository;
import com.melonhub.rbac.infrastructure.persistence.repository.DepartmentRepository;
import com.melonhub.rbac.service.ddd.domain.model.AdminCredentialAccount;
import com.melonhub.rbac.service.ddd.domain.model.AdminHierarchy;
import com.melonhub.rbac.service.ddd.domain.repository.AdminAccountRepositoryPort;
import com.melonhub.rbac.service.ddd.domain.value.AdminScope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** DDD 基础设施适配器：将 {@link AdminAccountRepositoryPort} 桥接到 Spring Data JPA 仓储。 */
@Repository
@ConditionalOnProperty(name = "melonhub.backend-strategy", havingValue = "ddd")
public class JpaAdminAccountRepositoryAdapter implements AdminAccountRepositoryPort {
    private final AdminAccountRepository accounts;
    private final AdminRoleRepository adminRoles;
    private final AdminSessionRepository sessions;
    private final ApplicationRepository applications;
    private final ApplicationKeyRepository keys;
    private final DepartmentRepository departments;

    public JpaAdminAccountRepositoryAdapter(AdminAccountRepository accounts, AdminRoleRepository adminRoles,
                                            AdminSessionRepository sessions, ApplicationRepository applications,
                                            ApplicationKeyRepository keys, DepartmentRepository departments) {
        this.accounts = accounts;
        this.adminRoles = adminRoles;
        this.sessions = sessions;
        this.applications = applications;
        this.keys = keys;
        this.departments = departments;
    }

    @Override public boolean accountExists(String username) { return accounts.existsByUsername(username); }

    @Override
    public long createAccount(String username, String displayName, String role, String passwordHash, boolean enabled,
                              Long parentId, String kind, boolean allDepartments, boolean allApplications) {
        return accounts.createAccount(username, displayName, adminRoles.getReferenceById(role), passwordHash,
                enabled, parentId, kind, allDepartments, allApplications);
    }

    @Override
    public void resetBootstrap(String username, String passwordHash, String superAdminRole) {
        accounts.resetBootstrap(username, passwordHash, adminRoles.getReferenceById(superAdminRole));
    }

    @Override
    public Optional<AdminCredentialAccount> findAccountByUsername(String username) {
        return accounts.credentials(username).map(JpaAdminAccountRepositoryAdapter::toAccount);
    }

    @Override
    public Optional<AdminCredentialAccount> findAccountById(long id) {
        return accounts.credentials(id).map(JpaAdminAccountRepositoryAdapter::toAccount);
    }

    @Override
    public boolean recordFailure(long id, int maxFailures, int lockMinutes) {
        return accounts.recordFailure(id, maxFailures, lockMinutes);
    }

    @Override
    public void recordSuccess(long id) {
        accounts.recordSuccess(id, nowSec());
    }

    @Override
    public int createSession(String tokenHash, AdminCredentialAccount account, Instant expiresAt, String sourceIp) {
        return accounts.createSessionIfCredentialsMatch(sessions, tokenHash, account.id(), account.credentialVersion(),
                account.passwordHash(), account.mfaSecret(), expiresAt, sourceIp);
    }

    @Override
    public Optional<SessionSnapshot> findActiveSession(String tokenHash, int idleMinutes) {
        Instant now = Instant.now();
        return sessions.active(tokenHash, now, now.minus(idleMinutes, ChronoUnit.MINUTES))
                .map(s -> new SessionSnapshot(s.getId(), s.getUsername(), s.getDisplayName(), s.getRole(),
                        s.isAllApplications(), s.getKind(), s.isRoot(), s.isAllDepartments(),
                        s.getLastSeenAt().isBefore(now.minusSeconds(60))));
    }

    @Override public void touchSession(String tokenHash) { sessions.touch(tokenHash, nowSec()); }
    @Override public void deleteSession(String tokenHash) { sessions.deleteByHashes(List.of(tokenHash)); }

    @Override
    @Transactional
    public void endAllSessions(long accountId) {
        accounts.bumpCredentialVersion(accountId);
        sessions.deleteForAccount(accountId);
    }

    @Override
    @Transactional
    public void keepOnlySession(long accountId, String tokenHash) {
        accounts.credentials(accountId).ifPresent(a -> sessions.moveToVersion(tokenHash, accountId, a.getCredentialVersion()));
        sessions.deleteOthers(accountId, tokenHash);
    }

    @Override public int replacePassword(long accountId, String expectedHash, String newHash) { return accounts.replacePassword(accountId, expectedHash, newHash, nowSec()); }
    @Override public int updatePassword(long accountId, String newHash) { return accounts.updatePassword(accountId, newHash, nowSec()); }
    @Override public void setMfaPendingSecret(long accountId, String secret) { accounts.setMfaPending(accountId, secret); }

    @Override
    public String findMfaPendingSecret(long accountId) {
        return accounts.pendingMfaSecret(accountId).stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    @Override public int confirmMfa(long accountId, String expectedPending, long step) { return accounts.confirmMfa(accountId, expectedPending, step); }
    @Override public int consumeMfaStep(long accountId, String expectedSecret, long step) { return accounts.consumeMfaStep(accountId, expectedSecret, step); }
    @Override public int disableMfaWithStep(long accountId, String expectedSecret, long step) { return accounts.disableMfaWithStep(accountId, expectedSecret, step); }
    @Override public int disableMfa(long accountId) { return accounts.disableMfa(accountId); }

    @Override
    public Optional<ApiKeySnapshot> findApiKeyByHash(String keyHash) {
        Instant now = Instant.now();
        return keys.byHash(keyHash).map(k -> new ApiKeySnapshot(
                k.getKeyId(), k.getApplicationId(),
                k.isApplicationEnabled() && k.getRevokedAt() == null
                        && (k.getExpiresAt() == null || k.getExpiresAt().isAfter(now)),
                k.getExpiresAt()));
    }

    @Override
    public void markKeysUsed(Collection<Long> keyIds) {
        if (!keyIds.isEmpty()) keys.markUsed(keyIds, nowSec());
    }

    @Override public int purgeSessions(int idleMinutes, int limit) { return sessions.purgeExpired(idleMinutes, limit); }
    @Override public void lockAllAccounts() { accounts.lockAll(); }

    @Override
    public List<AdminHierarchy.AdminNode> loadHierarchyNodes() {
        var deptMap = new HashMap<Long, Set<Long>>();
        for (var pair : accounts.departmentPairs()) {
            deptMap.computeIfAbsent(pair.getAccountId(), k -> new TreeSet<>()).add(pair.getDepartmentId());
        }
        var appMap = new HashMap<Long, Set<Long>>();
        for (var pair : accounts.applicationPairs()) {
            appMap.computeIfAbsent(pair.getAccountId(), k -> new TreeSet<>()).add(pair.getApplicationId());
        }
        return accounts.nodes().stream()
                .map(n -> new AdminHierarchy.AdminNode(n.getId(), n.getUsername(), n.getParentId(), n.getKind(),
                        n.getRole(), n.isEnabled(),
                        new AdminScope(n.isAllDepartments(), deptMap.getOrDefault(n.getId(), Set.of()),
                                n.isAllApplications(), appMap.getOrDefault(n.getId(), Set.of()))))
                .toList();
    }

    @Override
    public List<AccountSummaryRecord> listAccountRows() {
        Instant now = Instant.now();
        var sessionCounts = new HashMap<Long, Long>();
        for (var c : sessions.activeCounts(now)) sessionCounts.put(c.getAccountId(), c.getSessions());
        var rows = new ArrayList<AccountSummaryRecord>();
        for (AdminAccount a : accounts.listed()) {
            rows.add(new AccountSummaryRecord(
                    a.getId(), a.getUsername(), a.getDisplayName(), a.getRole().getCode(), a.isEnabled(),
                    a.getFailedAttempts(),
                    a.getLockedUntil() != null && a.getLockedUntil().isAfter(now) ? 1L : 0L,
                    a.getLockedUntil(), a.getLastLoginAt(), a.getPasswordChangedAt(), a.getCreatedAt(),
                    sessionCounts.getOrDefault(a.getId(), 0L),
                    a.getMfaSecret() != null ? 1L : 0L,
                    a.isAllApplications(),
                    a.getParent() == null ? null : a.getParent().getId(),
                    a.getParent() == null ? null : a.getParent().getUsername(),
                    a.getKind(), a.isAllDepartments()));
        }
        return rows;
    }

    @Override
    public List<AccountApplicationPair> accountApplicationPairs() {
        return accounts.applicationPairs().stream()
                .map(p -> new AccountApplicationPair(p.getAccountId(), p.getApplicationId())).toList();
    }

    @Override
    public List<AccountDepartmentPair> accountDepartmentPairs() {
        return accounts.departmentPairs().stream()
                .map(p -> new AccountDepartmentPair(p.getAccountId(), p.getDepartmentId())).toList();
    }

    @Override public List<Long> accountApplications(long accountId) { return accounts.applicationIds(accountId); }
    @Override public List<Long> accountDepartments(long accountId) { return accounts.departmentIds(accountId); }
    @Override public long countExistingApplications(Collection<Long> ids) { return ids.isEmpty() ? 0 : applications.countByIdIn(ids); }
    @Override public long countExistingDepartments(Collection<Long> ids) { return ids.isEmpty() ? 0 : departments.countByIdIn(ids); }
    @Override public void replaceAccountApplications(long accountId, Collection<Long> applicationIds) { accounts.replaceApplicationIds(accountId, applicationIds); }
    @Override public void replaceAccountDepartments(long accountId, Collection<Long> departmentIds) { accounts.replaceDepartmentIds(accountId, departmentIds); }

    @Override
    public void updateAccount(long id, String displayName, String role, boolean enabled, String kind,
                              boolean allDepartments, boolean allApplications) {
        accounts.updateAccount(id, displayName, adminRoles.getReferenceById(role), enabled, kind,
                allDepartments, allApplications);
    }

    @Override public void moveAccountUnder(long id, long parentId) { accounts.moveUnder(id, parentId); }
    @Override public int unlockAccount(long id) { return accounts.unlock(id); }
    @Override public int deleteAccount(long id) { return accounts.delete(id); }

    private static AdminCredentialAccount toAccount(AdminAccountRepository.Credentials row) {
        boolean locked = row.getLockedUntil() != null && row.getLockedUntil().isAfter(Instant.now());
        return new AdminCredentialAccount(
                row.getId(), row.getUsername(), row.getDisplayName(), row.getRole(), row.isEnabled(),
                row.getPasswordHash(), locked, row.getMfaSecret(), row.getMfaLastStep(),
                row.getCredentialVersion(), row.getKind(), row.isRoot(),
                new AdminScope(row.isAllDepartments(), Set.of(), row.isAllApplications(), Set.of()));
    }

    private static Instant nowSec() { return Instant.now().truncatedTo(ChronoUnit.SECONDS); }
}
