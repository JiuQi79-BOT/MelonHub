package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Department;
import com.melonhub.rbac.infrastructure.persistence.entity.User;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, Long>, UserRepositoryCustom {
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

    long countByEnabledTrue();

    @Query("""
            select u.id as id, u.name as name, u.externalId as externalId, u.email as email, u.enabled as enabled,
                   ad.id as activeDepartmentId, u.contextVersion as contextVersion, u.createdAt as createdAt
            from User u left join u.activeDepartment ad where u.id = :id""")
    Optional<UserSummary> findSummaryById(long id);

    @Query("select u.id from User u where u.externalId = :externalId")
    Optional<Long> findIdByExternalId(String externalId);

    @Query("select u.id as id, u.externalId as externalId, u.name as name from User u where u.externalId = :externalId and u.enabled = true")
    Optional<IdentityUser> findActiveIdentityUserByExternalId(String externalId);

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
}
