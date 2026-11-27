package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.Department;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface DepartmentRepository extends JpaRepository<Department, Long>, DepartmentRepositoryCustom {
    interface Label {
        String getName();
        String getCode();
    }

    interface CodeId {
        long getId();
        String getCode();
    }

    long countByIdIn(Collection<Long> ids);

    @Query("select d.name as name, d.code as code from Department d where d.id = :id")
    Optional<Label> findLabelById(long id);

    @Query("select d.id as id, d.code as code from Department d where d.code in :codes")
    List<CodeId> findCodeIdsByCodeIn(Collection<String> codes);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Department d set d.name = :name, d.code = :code where d.id = :id")
    int updateProfile(long id, String name, String code);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Department d where d.id = :id")
    int deleteByIdReturningCount(long id);
}
