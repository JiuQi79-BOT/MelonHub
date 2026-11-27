package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Application;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {
    record SummaryRow(long id, String name, String code, String description, String owner,
                      boolean enabled, Integer rateLimit, Instant createdAt) {}

    record SearchRow(long id, String name, String code, boolean enabled) {}

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

    interface SummaryProjection {
        long getId();
        String getName();
        String getCode();
        String getDescription();
        String getOwner();
        boolean isEnabled();
        Integer getRateLimit();
        Instant getCreatedAt();
    }

    interface SearchProjection {
        long getId();
        String getName();
        String getCode();
        boolean isEnabled();
    }

    long countByIdIn(Collection<Long> ids);

    long countByEnabledTrue();

    boolean existsByIdAndEnabledTrue(long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Application a where a.id = :id")
    Optional<Application> findLockedById(long id);

    @Query("select a.name as name, a.code as code from Application a where a.id = :id")
    Optional<Label> findLabelById(long id);

    @Query("select a.id as id, a.name as name, a.code as code, a.enabled as enabled from Application a where a.id = :id")
    Optional<Ref> findRefById(long id);

    @Query("select a.rateLimit from Application a where a.id = :id")
    Optional<Integer> findRateLimitById(long id);

    @Query("""
            select a.id as id, a.name as name, a.code as code, a.description as description, a.owner as owner,
                   a.enabled as enabled, a.rateLimit as rateLimit, a.createdAt as createdAt
            from Application a
            where (:filterVisible = false or a.id in :visibleIds)
            order by a.id""")
    List<SummaryProjection> findSummaryProjections(boolean filterVisible, Collection<Long> visibleIds);

    @Query("""
            select a.id as id, a.name as name, a.code as code, a.enabled as enabled
            from Application a
            where (a.name like :pattern escape '!' or a.code like :pattern escape '!')
              and (:filterVisible = false or a.id in :visibleIds)
            order by a.id""")
    List<SearchProjection> searchProjections(String pattern, boolean filterVisible, Collection<Long> visibleIds, Limit limit);

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

    @Transactional
    default boolean lockById(long id) {
        return findLockedById(id).isPresent();
    }

    default List<SummaryRow> findVisibleSummaries(Collection<Long> visibleIds) {
        boolean filter = visibleIds != null;
        if (filter && visibleIds.isEmpty()) return List.of();
        return findSummaryProjections(filter, filter ? visibleIds : RepositorySupport.NO_IDS).stream()
                .map(p -> new SummaryRow(p.getId(), p.getName(), p.getCode(), p.getDescription(), p.getOwner(),
                        p.isEnabled(), p.getRateLimit(), p.getCreatedAt()))
                .toList();
    }

    default List<SearchRow> searchVisible(String keyword, Collection<Long> visibleIds) {
        boolean filter = visibleIds != null;
        if (filter && visibleIds.isEmpty()) return List.of();
        return searchProjections(RepositorySupport.like(keyword), filter,
                filter ? visibleIds : RepositorySupport.NO_IDS, Limit.of(5)).stream()
                .map(p -> new SearchRow(p.getId(), p.getName(), p.getCode(), p.isEnabled()))
                .toList();
    }
}
