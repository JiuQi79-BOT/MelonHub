package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AdminSession;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AdminSessionRepository extends JpaRepository<AdminSession, String> {
    interface ActiveSession {
        long getId();
        String getUsername();
        String getDisplayName();
        String getRole();
        Instant getLastSeenAt();
        boolean isAllApplications();
        String getKind();
        boolean isRoot();
        boolean isAllDepartments();
    }
    interface SessionCount {
        long getAccountId();
        long getSessions();
    }

    /** A session is usable while unexpired, recently used, created under the current credentials and the account is enabled. */
    @Query("""
            select a.id as id, a.username as username, a.displayName as displayName, a.role.code as role, s.lastSeenAt as lastSeenAt,
                   a.allApplications as allApplications, a.kind as kind, case when a.parent is null then true else false end as root,
                   a.allDepartments as allDepartments
            from AdminSession s join s.account a
            where s.tokenHash = :hash and s.credentialVersion = a.credentialVersion and a.enabled = true
              and s.expiresAt > :now and s.lastSeenAt > :idleSince""")
    Optional<ActiveSession> active(String hash, Instant now, Instant idleSince);

    @Query("select s.account.id as accountId, count(s) as sessions from AdminSession s where s.expiresAt > :now group by s.account.id")
    List<SessionCount> activeCounts(Instant now);

    @Query("select s.tokenHash from AdminSession s where s.expiresAt < :now or s.lastSeenAt < :idleSince")
    List<String> ended(Instant now, Instant idleSince, Limit limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminSession s set s.lastSeenAt = :now where s.tokenHash = :hash")
    int touch(String hash, Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminSession s set s.credentialVersion = :version where s.tokenHash = :hash and s.account.id = :accountId")
    int moveToVersion(String hash, long accountId, long version);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminSession s where s.tokenHash in :hashes")
    int deleteByHashes(Collection<String> hashes);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminSession s where s.account.id = :accountId")
    int deleteForAccount(long accountId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminSession s where s.account.id = :accountId and s.tokenHash <> :keepHash")
    int deleteOthers(long accountId, String keepHash);

    @Transactional
    default int purgeExpired(int idleMinutes, int limit) {
        Instant now = Instant.now();
        Instant idleSince = now.minus(idleMinutes, ChronoUnit.MINUTES);
        int removed = 0;
        while (removed < limit) {
            var batch = ended(now, idleSince, Limit.of(Math.min(1000, limit - removed)));
            if (batch.isEmpty()) break;
            removed += deleteByHashes(batch);
            if (batch.size() < 1000) break;
        }
        return removed;
    }
}
