package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AdminAccount;
import com.melonhub.rbac.infrastructure.persistence.entity.AdminRole;
import com.melonhub.rbac.infrastructure.persistence.entity.AdminSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AdminAccountRepositoryImpl implements AdminAccountRepositoryCustom {
    private final EntityManager em;

    AdminAccountRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<AdminAccount> lockAll() {
        return JpaQuery.lockedList(em,
                em.createQuery("select a from AdminAccount a order by a.id", AdminAccount.class),
                LockModeType.PESSIMISTIC_WRITE);
    }

    private AdminAccount locked(long id, LockModeType mode) {
        AdminAccount account = em.find(AdminAccount.class, id);
        if (account == null) return null;
        try {
            em.refresh(account, mode);
        } catch (EntityNotFoundException deleted) {
            return null;
        }
        return account;
    }

    @Override
    public long createAccount(String username, String displayName, String roleCode, String passwordHash,
                              boolean enabled, Long parentId, String kind, boolean allDepartments, boolean allApplications) {
        var account = new AdminAccount(username, displayName, em.getReference(AdminRole.class, roleCode), passwordHash,
                enabled, parentId == null ? null : em.getReference(AdminAccount.class, parentId),
                kind, allDepartments, allApplications, JpaQuery.now());
        em.persist(account);
        em.flush();
        return account.getId();
    }

    @Override
    public void resetBootstrap(String username, String passwordHash, String superAdminRole) {
        em.flush();
        em.createQuery("""
                update AdminAccount a set a.passwordHash = :hash, a.role = :role, a.allApplications = true,
                       a.allDepartments = true, a.kind = 'MANAGER', a.parent = null, a.enabled = true,
                       a.failedAttempts = 0, a.lockedUntil = null, a.passwordChangedAt = :now,
                       a.credentialVersion = a.credentialVersion + 1
                where a.username = :username""")
                .setParameter("hash", passwordHash)
                .setParameter("role", em.getReference(AdminRole.class, superAdminRole))
                .setParameter("now", JpaQuery.now())
                .setParameter("username", username)
                .executeUpdate();
        em.clear();
    }

    @Override
    public int updateAccount(long id, String displayName, String roleCode, boolean enabled, String kind,
                             boolean allDepartments, boolean allApplications) {
        em.flush();
        int changed = em.createQuery("""
                update AdminAccount a set a.displayName = :displayName, a.role = :role, a.enabled = :enabled,
                       a.kind = :kind, a.allDepartments = :allDepartments, a.allApplications = :allApplications
                where a.id = :id""")
                .setParameter("displayName", displayName)
                .setParameter("role", em.getReference(AdminRole.class, roleCode))
                .setParameter("enabled", enabled)
                .setParameter("kind", kind)
                .setParameter("allDepartments", allDepartments)
                .setParameter("allApplications", allApplications)
                .setParameter("id", id)
                .executeUpdate();
        em.clear();
        return changed;
    }

    @Override
    public void moveUnder(long id, long parentId) {
        em.flush();
        em.createQuery("update AdminAccount a set a.parent = :parent where a.id = :id")
                .setParameter("parent", em.getReference(AdminAccount.class, parentId))
                .setParameter("id", id)
                .executeUpdate();
        em.clear();
    }

    @Override
    public boolean recordFailure(long id, int maxFailures, int lockMinutes) {
        AdminAccount account = locked(id, LockModeType.PESSIMISTIC_WRITE);
        if (account == null) return false;
        Instant now = JpaQuery.now();
        if (account.getFailedAttempts() + 1 >= maxFailures) {
            account.setLockedUntil(now.plus(lockMinutes, ChronoUnit.MINUTES));
            account.setFailedAttempts(0);
        } else {
            account.setFailedAttempts(account.getFailedAttempts() + 1);
        }
        em.flush();
        return account.getLockedUntil() != null && account.getLockedUntil().isAfter(now);
    }

    @Override
    public int createSessionIfCredentialsMatch(String tokenHash, long accountId, long expectedVersion,
                                               String expectedPasswordHash, String expectedMfaSecret,
                                               Instant expiresAt, String sourceIp) {
        AdminAccount current = locked(accountId, LockModeType.PESSIMISTIC_READ);
        if (current == null || !current.isEnabled()
                || current.getCredentialVersion() != expectedVersion
                || !current.getPasswordHash().equals(expectedPasswordHash)
                || !Objects.equals(current.getMfaSecret(), expectedMfaSecret)) {
            return 0;
        }
        em.persist(new AdminSession(tokenHash, current, expiresAt, JpaQuery.now(), sourceIp, current.getCredentialVersion()));
        em.flush();
        return 1;
    }

    @Override
    public void replaceDepartmentIds(long accountId, Collection<Long> departmentIds) {
        AdminAccount account = em.find(AdminAccount.class, accountId);
        if (account == null) return;
        account.getDepartmentIds().clear();
        account.getDepartmentIds().addAll(departmentIds);
        em.flush();
    }

    @Override
    public void replaceApplicationIds(long accountId, Collection<Long> applicationIds) {
        AdminAccount account = em.find(AdminAccount.class, accountId);
        if (account == null) return;
        account.getApplicationIds().clear();
        account.getApplicationIds().addAll(applicationIds);
        em.flush();
    }
}
