package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AdminAccount;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * Console accounts. Reads that decide something select scalars, so they always see the database row rather than an
 * entity this persistence context loaded earlier. Updates keep the conditions that make concurrent requests safe
 * (password still the one proven, code step still unused) and report how many rows changed.
 */
public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long>, AdminAccountRepositoryCustom {
    interface Credentials {
        long getId();
        String getUsername();
        String getDisplayName();
        String getRole();
        boolean isEnabled();
        String getPasswordHash();
        Instant getLockedUntil();
        String getMfaSecret();
        long getMfaLastStep();
        long getCredentialVersion();
        boolean isAllApplications();
        String getKind();
        boolean isRoot();
        boolean isAllDepartments();
    }
    interface AccountApplication {
        long getAccountId();
        Long getApplicationId();
    }
    interface AccountDepartment {
        long getAccountId();
        Long getDepartmentId();
    }
    /** One account's place in the administrator tree. */
    interface Node {
        long getId();
        String getUsername();
        Long getParentId();
        String getKind();
        String getRole();
        boolean isEnabled();
        boolean isAllDepartments();
        boolean isAllApplications();
    }

    String CREDENTIALS = """
            select a.id as id, a.username as username, a.displayName as displayName, a.role.code as role, a.enabled as enabled,
                   a.passwordHash as passwordHash, a.lockedUntil as lockedUntil, a.mfaSecret as mfaSecret,
                   a.mfaLastStep as mfaLastStep, a.credentialVersion as credentialVersion, a.allApplications as allApplications,
                   a.kind as kind, case when a.parent is null then true else false end as root, a.allDepartments as allDepartments
            from AdminAccount a
            """;

    @Query(CREDENTIALS + "where a.username = :username")
    Optional<Credentials> credentials(String username);

    @Query(CREDENTIALS + "where a.id = :id")
    Optional<Credentials> credentials(long id);

    boolean existsByUsername(String username);

    @Query("select a from AdminAccount a join fetch a.role left join fetch a.parent order by a.createdAt, a.username")
    List<AdminAccount> listed();

    @Query("select a.id as accountId, app as applicationId from AdminAccount a join a.applicationIds app")
    List<AccountApplication> applicationPairs();

    @Query("select app from AdminAccount a join a.applicationIds app where a.id = :id order by app")
    List<Long> applicationIds(long id);

    @Query("select a.id as accountId, d as departmentId from AdminAccount a join a.departmentIds d")
    List<AccountDepartment> departmentPairs();

    @Query("select d from AdminAccount a join a.departmentIds d where a.id = :id order by d")
    List<Long> departmentIds(long id);

    @Query("""
            select a.id as id, a.username as username, p.id as parentId, a.kind as kind, a.role.code as role, a.enabled as enabled,
                   a.allDepartments as allDepartments, a.allApplications as allApplications
            from AdminAccount a left join a.parent p""")
    List<Node> nodes();

    @Query("select count(a) from AdminAccount a join a.departmentIds d where d = :departmentId")
    long countLimitedToDepartment(long departmentId);

    @Query("select a.mfaPendingSecret from AdminAccount a where a.id = :id")
    List<String> pendingMfaSecret(long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.failedAttempts = 0, a.lockedUntil = null, a.lastLoginAt = :now where a.id = :id")
    int recordSuccess(long id, Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.credentialVersion = a.credentialVersion + 1 where a.id = :id")
    int bumpCredentialVersion(long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AdminAccount a set a.passwordHash = :hash, a.passwordChangedAt = :now, a.failedAttempts = 0, a.lockedUntil = null,
                   a.credentialVersion = a.credentialVersion + 1
            where a.id = :id""")
    int updatePassword(long id, String hash, Instant now);

    /** Changes the password only if it is still the one the caller proved, so two concurrent changes cannot both win. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AdminAccount a set a.passwordHash = :hash, a.passwordChangedAt = :now, a.failedAttempts = 0, a.lockedUntil = null,
                   a.credentialVersion = a.credentialVersion + 1
            where a.id = :id and a.passwordHash = :expectedHash""")
    int replacePassword(long id, String expectedHash, String hash, Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.failedAttempts = 0, a.lockedUntil = null where a.id = :id")
    int unlock(long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminAccount a where a.id = :id")
    int delete(long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.mfaPendingSecret = :secret where a.id = :id")
    int setMfaPending(long id, String secret);

    /** Activates exactly the pending secret the code was checked against; a newer setup in between makes this a no-op. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AdminAccount a set a.mfaSecret = :pendingSecret, a.mfaPendingSecret = null, a.mfaLastStep = :step
            where a.id = :id and a.mfaPendingSecret = :pendingSecret""")
    int confirmMfa(long id, String pendingSecret, long step);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.mfaSecret = null, a.mfaPendingSecret = null, a.mfaLastStep = 0 where a.id = :id")
    int disableMfa(long id);

    /**
     * Uses a code step at most once: the row only changes while the step is newer than the last used one, so of two
     * concurrent requests with the same code exactly one gets 1 row back.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.mfaLastStep = :step where a.id = :id and a.mfaSecret = :secret and a.mfaLastStep < :step")
    int consumeMfaStep(long id, String secret, long step);

    /** Disabling spends the code the same way, so it cannot race a login that uses the same code. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AdminAccount a set a.mfaSecret = null, a.mfaPendingSecret = null, a.mfaLastStep = 0
            where a.id = :id and a.mfaSecret = :secret and a.mfaLastStep < :step""")
    int disableMfaWithStep(long id, String secret, long step);
}
