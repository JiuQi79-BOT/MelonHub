package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Membership;
import com.melonhub.rbac.infrastructure.persistence.entity.MembershipId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface MembershipRepository extends JpaRepository<Membership, MembershipId>, MembershipRepositoryCustom {
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

    interface DepartmentCount {
        long getDepartmentId();
        long getCount();
    }

    long countByDepartmentId(long departmentId);

    boolean existsByUserIdAndDepartmentId(long userId, long departmentId);

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

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Membership m where m.user.id = :userId and m.department.id = :departmentId")
    int deleteByUserIdAndDepartmentId(long userId, long departmentId);
}
