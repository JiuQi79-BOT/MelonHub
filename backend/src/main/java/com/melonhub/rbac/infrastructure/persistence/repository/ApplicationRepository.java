package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Application;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ApplicationRepository extends JpaRepository<Application, Long>, ApplicationRepositoryCustom {
    interface Label {
        String getName();
        String getCode();
    }

    interface Ref {
        long getId();
        String getName();
        String getCode();
        boolean isEnabled();
    }

    long countByIdIn(Collection<Long> ids);

    long countByEnabledTrue();

    boolean existsByIdAndEnabledTrue(long id);

    @Query("select a.name as name, a.code as code from Application a where a.id = :id")
    Optional<Label> findLabelById(long id);

    @Query("select a.id as id, a.name as name, a.code as code, a.enabled as enabled from Application a where a.id = :id")
    Optional<Ref> findRefById(long id);

    @Query("select a.rateLimit from Application a where a.id = :id")
    Optional<Integer> findRateLimitById(long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Application a set a.name = :name, a.code = :code, a.description = :description, a.owner = :owner where a.id = :id")
    int updateProfile(long id, String name, String code, String description, String owner);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Application a set a.enabled = :enabled where a.id = :id")
    int updateStatus(long id, boolean enabled);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Application a set a.rateLimit = :limit where a.id = :id")
    int updateRateLimit(long id, Integer limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Application a where a.id = :id")
    int deleteByIdReturningCount(long id);
}
