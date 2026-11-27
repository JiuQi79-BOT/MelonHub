package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Department;
import com.melonhub.rbac.infrastructure.persistence.entity.Membership;
import com.melonhub.rbac.infrastructure.persistence.entity.MembershipId;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {
    record MemberRow(long id, String name, String externalId, String email, boolean enabled) {}

    record IdentityDepartmentUserRow(String userId, String no, String name, long departmentId, String departmentName) {}

    interface MembershipPair {
        long getUserId();
        long getDepartmentId();
    }

    interface DepartmentUser {
        long getId();
        String getName();
        String getExternalId();
        boolean isEnabled();
    }

    interface MemberProjection {
        long getId();
        String getName();
        String getExternalId();
        String getEmail();
        boolean isEnabled();
    }

    interface IdentityDepartmentUserProjection {
        String getUserId();
        String getNo();
        String getName();
        long getDepartmentId();
        String getDepartmentName();
    }

    interface DepartmentCount {
        long getDepartmentId();
        long getCount();
    }

    long countByDepartmentId(long departmentId);

    boolean existsByUserIdAndDepartmentId(long userId, long departmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.user.id = :userId")
    List<Membership> findLockedByUserId(long userId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select m from Membership m where m.user.id = :userId and m.department.id = :departmentId")
    Optional<Membership> findReadLocked(long userId, long departmentId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select m from Membership m where m.department.id = :departmentId and m.user.id in :userIds")
    List<Membership> findReadLockedByDepartmentAndUsers(long departmentId, Collection<Long> userIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.id = :id")
    Optional<Membership> findLockedById(MembershipId id);

    @Query("select m.department.id from Membership m where m.user.id = :userId")
    List<Long> findDepartmentIdsByUserId(long userId);

    @Query("select m.user.id as userId, m.department.id as departmentId from Membership m where m.user.id in :userIds order by m.department.id")
    List<MembershipPair> findPairsByUserIdIn(Collection<Long> userIds);

    @Query("""
            select u.id as id, u.name as name, u.externalId as externalId, u.enabled as enabled
            from Membership m join m.user u where m.department.id = :departmentId order by u.id""")
    List<DepartmentUser> findDepartmentUsers(long departmentId);

    @Query("select m.department.id as departmentId, count(m) as count from Membership m where m.department.id in :departmentIds group by m.department.id")
    List<DepartmentCount> countByDepartmentIds(Collection<Long> departmentIds);

    @Query(value = """
            select u.id as id, u.name as name, u.externalId as externalId, u.email as email, u.enabled as enabled
            from Membership m join m.user u
            where m.department.id = :departmentId
              and (:pattern is null
                   or u.name like :pattern escape '!'
                   or u.externalId like :pattern escape '!'
                   or u.email like :pattern escape '!')
            order by u.id""",
            countQuery = """
            select count(m) from Membership m join m.user u
            where m.department.id = :departmentId
              and (:pattern is null
                   or u.name like :pattern escape '!'
                   or u.externalId like :pattern escape '!'
                   or u.email like :pattern escape '!')""")
    Page<MemberProjection> findDepartmentMembersPageProjections(long departmentId, String pattern, Pageable pageable);

    @Query("""
            select u.externalId as userId, u.externalId as no, u.name as name,
                   d.id as departmentId, d.name as departmentName
            from Membership m join m.user u join m.department d
            where d.id = :departmentId and u.enabled = true
              and (:pattern is null or u.externalId like :pattern escape '!' or u.name like :pattern escape '!')
            order by u.id""")
    List<IdentityDepartmentUserProjection> findIdentityDepartmentUserProjections(long departmentId, String pattern, Pageable pageable);

    @Query("""
            select count(m) from Membership m join m.user u join m.department d
            where d.id = :departmentId and u.enabled = true
              and (:pattern is null or u.externalId like :pattern escape '!' or u.name like :pattern escape '!')""")
    long countIdentityDepartmentUserRows(long departmentId, String pattern);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Membership m where m.user.id = :userId and m.department.id = :departmentId")
    int deleteByUserIdAndDepartmentId(long userId, long departmentId);

    @Transactional
    default List<Long> findLockedDepartmentIds(long userId) {
        return findLockedByUserId(userId).stream()
                .map(m -> m.getId().getDepartmentId())
                .toList();
    }

    @Transactional
    default boolean existsLocked(long userId, long departmentId) {
        return findReadLocked(userId, departmentId).isPresent();
    }

    @Transactional
    default long countLocked(Collection<Long> userIds, long departmentId) {
        if (userIds.isEmpty()) return 0L;
        return findReadLockedByDepartmentAndUsers(departmentId, userIds).size();
    }

    @Transactional
    default void addIfAbsent(long userId, long departmentId, LongFunction<User> userRef,
                             LongFunction<Department> departmentRef) {
        var id = new MembershipId(userId, departmentId);
        if (findLockedById(id).isPresent()) return;
        saveAndFlush(new Membership()
                .setId(id)
                .setUser(userRef.apply(userId))
                .setDepartment(departmentRef.apply(departmentId)));
    }

    default Page<MemberRow> findDepartmentMembersPage(long departmentId, String keyword, int page, int size) {
        return findDepartmentMembersPageProjections(departmentId, RepositorySupport.likeOrNull(keyword),
                RepositorySupport.page(page, size))
                .map(p -> new MemberRow(p.getId(), p.getName(), p.getExternalId(), p.getEmail(), p.isEnabled()));
    }

    default List<IdentityDepartmentUserRow> findIdentityDepartmentUsers(long departmentId, String keyword, int page, int size) {
        return findIdentityDepartmentUserProjections(departmentId, RepositorySupport.likeOrNull(keyword),
                RepositorySupport.page(page, size)).stream()
                .map(p -> new IdentityDepartmentUserRow(
                        p.getUserId(), p.getNo(), p.getName(), p.getDepartmentId(), p.getDepartmentName()))
                .toList();
    }

    default long countIdentityDepartmentUsers(long departmentId, String keyword) {
        return countIdentityDepartmentUserRows(departmentId, RepositorySupport.likeOrNull(keyword));
    }
}
