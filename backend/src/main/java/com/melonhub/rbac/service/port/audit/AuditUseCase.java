package com.melonhub.rbac.service.port.audit;

import com.melonhub.rbac.service.port.audit.AuditModels.AuditEventResult;
import com.melonhub.rbac.service.port.common.PageResult;
import com.melonhub.rbac.service.port.security.Visibility;
import java.time.LocalDate;

/**
 * 审计事件记录与分页检索用例端口。
 */
public interface AuditUseCase {
    default void audit(String actor, String action, String target, Long department, Long app) {
        audit(actor, action, target, department, app, "");
    }

    void audit(String actor, String action, String target, Long department, Long app, String detail);

    PageResult<AuditEventResult> events(Long dept, Long app, Visibility visible, String actor, String action,
                                        String keyword, LocalDate from, LocalDate to, int page, int size);
}
