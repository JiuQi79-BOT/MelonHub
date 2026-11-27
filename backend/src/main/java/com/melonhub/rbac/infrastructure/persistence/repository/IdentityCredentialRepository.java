package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.IdentityCredential;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface IdentityCredentialRepository
        extends JpaRepository<IdentityCredential, Long>, IdentityCredentialRepositoryCustom {

    interface ActiveCredential {
        long getUserId();
        String getUsername();
        String getPasswordHash();
    }

    @Query("""
            select c.userId as userId, c.username as username, c.passwordHash as passwordHash
            from IdentityCredential c join c.user u
            where c.username = :username and c.enabled = true and u.enabled = true""")
    Optional<ActiveCredential> findActiveByUsername(String username);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update IdentityCredential c set c.username = :username where c.userId = :userId")
    int renameUsername(long userId, String username);
}
