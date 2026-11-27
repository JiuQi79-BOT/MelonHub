package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.service.port.security.Visibility;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

/** 审计日志与课程业务审计的动态分页查询片段。 */
public interface AuditEventRepositoryCustom {
    record AuditEventRow(long id, String actor, String action, String target, String detail, String sourceIp,
                         Instant createdAt, Long departmentId, String departmentName,
                         Long applicationId, String applicationName) {}

    record AuditFilter(Long departmentId, Long applicationId, Visibility visible, String actor, String action,
                       String keyword, Instant from, Instant to) {}

    record CourseAuditRow(long id, Instant at, String type, String target, String actorNo,
                          Long actorId, String actorName) {}

    Page<AuditEventRow> findAuditPage(AuditFilter filter, int page, int size);

    Page<CourseAuditRow> findCourseAuditPage(String applicationCode, List<Long> departmentIds, String actor,
                                             Long actorId, String target, Instant from, Instant to,
                                             int page, int pageSize);
}
