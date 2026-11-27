package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.service.port.security.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;

/** 角色申请的快照创建、动态分页查询与待审批统计片段。 */
public interface AccessRequestRepositoryCustom {
    record AccessRequestRow(long id, Long userId, String userName, String externalId, Long departmentId,
                            String departmentName, long applicationId, String applicationName, Long roleId,
                            String roleName, String roleCode, Boolean roleEnabled, String reason,
                            Integer durationDays, String status, String requestedBy, String decidedBy,
                            Instant decidedAt, String decisionComment, Instant grantExpiresAt,
                            Instant createdAt) {}

    long createRequest(long userId, long departmentId, long applicationId, long roleId,
                       String reason, Integer durationDays, String requestedBy);

    Optional<AccessRequestRow> findDetailById(long id);

    Page<AccessRequestRow> findPage(Long applicationId, Visibility visible, String status,
                                    String keyword, int page, int size);

    List<AccessRequestRow> findRecentByUserAndApplication(long userId, long applicationId, int limit);

    long countPending(Visibility visible);
}
