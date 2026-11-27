package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Department;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, Long> {
    record UserRow(long id, String name, String externalId, String email, boolean enabled,
                   Long activeDepartmentId, long contextVersion, Instant createdAt) {}

    record LockedUserRow(long id, boolean enabled, Long activeDepartmentId, long contextVersion) {}

    record SearchRow(long id, String name, String externalId, boolean enabled) {}

    interface UserSummary {
        long getId();
        String getName();
        String getExternalId();
        String getEmail();
        boolean isEnabled();
        Long getActiveDepartmentId();
        long getContextVersion();
        Instant getCreatedAt();
    }

    interface IdentityUser {
        long getId();
        String getExternalId();
        String getName();
    }

    interface SearchProjection {
        long getId();
        String getName();
        String getExternalId();
        boolean isEnabled();
    }

    long countByEnabledTrue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findLockedById(long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select u from User u where u.id = :id")
    Optional<User> findReadLockedById(long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id in :ids order by u.id")
    List<User> findLockedByIdInOrderByIdAsc(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.externalId = :externalId")
    Optional<User> findLockedByExternalId(String externalId);

    @Query("""
            select u.id as id, u.name as name, u.externalId as externalId, u.email as email, u.enabled as enabled,
                   ad.id as activeDepartmentId, u.contextVersion as contextVersion, u.createdAt as createdAt
            from User u left join u.activeDepartment ad where u.id = :id""")
    Optional<UserSummary> findSummaryById(long id);

    @Query("select u.id from User u where u.externalId = :externalId")
    Optional<Long> findIdByExternalId(String externalId);

    @Query("select u.id as id, u.externalId as externalId, u.name as name from User u where u.externalId = :externalId and u.enabled = true")
    Optional<IdentityUser> findActiveIdentityUserByExternalId(String externalId);

    @Query(value = """
            select u.id as id, u.name as name, u.externalId as externalId, u.email as email, u.enabled as enabled,
                   ad.id as activeDepartmentId, u.contextVersion as contextVersion, u.createdAt as createdAt
            from User u left join u.activeDepartment ad
            where (:filterVisible = false or exists (select 1 from Membership mv where mv.user.id = u.id and mv.department.id in :visibleDepartments))
              and (:enabled is null or u.enabled = :enabled)
              and (:departmentId is null or exists (select 1 from Membership m where m.user.id = u.id and m.department.id = :departmentId))
              and (:pattern is null
                   or u.name like :pattern escape '!'
                   or u.externalId like :pattern escape '!'
                   or u.email like :pattern escape '!')
            order by u.id""",
            countQuery = """
            select count(u) from User u
            where (:filterVisible = false or exists (select 1 from Membership mv where mv.user.id = u.id and mv.department.id in :visibleDepartments))
              and (:enabled is null or u.enabled = :enabled)
              and (:departmentId is null or exists (select 1 from Membership m where m.user.id = u.id and m.department.id = :departmentId))
              and (:pattern is null
                   or u.name like :pattern escape '!'
                   or u.externalId like :pattern escape '!'
                   or u.email like :pattern escape '!')""")
    Page<UserSummary> findPageProjections(String pattern, Long departmentId, Boolean enabled,
                                          boolean filterVisible, Collection<Long> visibleDepartments,
                                          Pageable pageable);

    @Query("""
            select u.id as id, u.name as name, u.externalId as externalId, u.enabled as enabled
            from User u
            where (u.name like :pattern escape '!' or u.externalId like :pattern escape '!' or u.email like :pattern escape '!')
              and (:filterVisible = false or exists (select 1 from Membership mv where mv.user.id = u.id and mv.department.id in :visibleDepartments))
            order by u.id""")
    List<SearchProjection> searchProjections(String pattern, boolean filterVisible,
                                             Collection<Long> visibleDepartments, Limit limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.name = :name, u.email = :email where u.id = :id")
    int updateProfile(long id, String name, String email);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.name = :name, u.externalId = :externalId, u.email = :email, u.contextVersion = u.contextVersion + :step where u.id = :id")
    int updateUser(long id, String name, String externalId, String email, long step);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.enabled = :enabled, u.contextVersion = u.contextVersion + 1 where u.id = :id")
    int updateStatus(long id, boolean enabled);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.contextVersion = u.contextVersion + 1 where u.id = :id")
    int bumpContextVersion(long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.activeDepartment = :department, u.contextVersion = :version where u.id = :id")
    int switchDepartment(long id, Department department, long version);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update User u set u.activeDepartment = null
            where u.id = :userId and u.activeDepartment.id not in (select m.department.id from Membership m where m.user.id = :userId)""")
    int clearInactiveDepartment(long userId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from User u where u.id = :id")
    int deleteByIdReturningCount(long id);

    @Transactional
    default Optional<String> lockExternalIdById(long id) {
        return findLockedById(id).map(User::getExternalId);
    }

    @Transactional
    default List<Long> lockIdsByIdIn(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return findLockedByIdInOrderByIdAsc(ids).stream().map(User::getId).toList();
    }

    @Transactional
    default Optional<Long> lockIdByExternalId(String externalId) {
        return findLockedByExternalId(externalId).map(User::getId);
    }

    @Transactional
    default Optional<LockedUserRow> lockRowByExternalId(String externalId) {
        return findLockedByExternalId(externalId).map(user -> new LockedUserRow(
                user.getId(),
                user.isEnabled(),
                user.getActiveDepartment() == null ? null : user.getActiveDepartment().getId(),
                user.getContextVersion()));
    }

    default Page<UserRow> findPage(String keyword, Long departmentId, Boolean enabled,
                                   Collection<Long> visibleDepartments, int page, int size) {
        var pageable = RepositorySupport.page(page, size);
        boolean filterVisible = visibleDepartments != null;
        if (filterVisible && visibleDepartments.isEmpty()) {
            return Page.empty(pageable);
        }
        return findPageProjections(RepositorySupport.likeOrNull(keyword), departmentId, enabled,
                filterVisible, filterVisible ? visibleDepartments : RepositorySupport.NO_IDS, pageable)
                .map(u -> new UserRow(u.getId(), u.getName(), u.getExternalId(), u.getEmail(),
                        u.isEnabled(), u.getActiveDepartmentId(), u.getContextVersion(), u.getCreatedAt()));
    }

    default List<SearchRow> searchVisible(String keyword, Collection<Long> visibleDepartments) {
        boolean filterVisible = visibleDepartments != null;
        if (filterVisible && visibleDepartments.isEmpty()) return List.of();
        return searchProjections(RepositorySupport.like(keyword), filterVisible,
                filterVisible ? visibleDepartments : RepositorySupport.NO_IDS, Limit.of(5)).stream()
                .map(p -> new SearchRow(p.getId(), p.getName(), p.getExternalId(), p.isEnabled()))
                .toList();
    }
}
