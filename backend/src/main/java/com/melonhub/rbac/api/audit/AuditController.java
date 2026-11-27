package com.melonhub.rbac.api.audit;

import static com.melonhub.rbac.service.port.security.AdminFunction.AUDIT_VIEW;

import com.melonhub.rbac.api.audit.vo.AuditEventVO;
import com.melonhub.rbac.api.audit.vo.AuditVerifyVO;
import com.melonhub.rbac.api.shared.vo.PageVO;
import com.melonhub.rbac.infrastructure.security.AdminAccess;
import com.melonhub.rbac.infrastructure.security.RequiresFunction;
import com.melonhub.rbac.service.port.audit.AuditSealUseCase;
import com.melonhub.rbac.service.port.audit.AuditUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台审计日志查询、快照导出与 SHA-256 哈希链校验接口（{@code /api/admin/audit/**}）。
 */
@RestController
@RequestMapping("/api/admin/audit")
public class AuditController {
    private final AuditUseCase audit;
    private final AuditSealUseCase auditSeal;

    public AuditController(AuditUseCase audit, AuditSealUseCase auditSeal) {
        this.audit = audit;
        this.auditSeal = auditSeal;
    }

    @RequiresFunction(AUDIT_VIEW)
    @GetMapping
    public PageVO<AuditEventVO> list(@RequestParam(required = false) Long departmentId,
                                     @RequestParam(required = false) Long applicationId,
                                     @RequestParam(required = false) String actor,
                                     @RequestParam(required = false) String action,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     HttpServletRequest req) {
        return AuditVoMapper.toAuditPageVO(audit.events(departmentId, applicationId, AdminAccess.of(req).visibility(),
                actor, action, keyword, from, to, Math.max(1, page), Math.min(200, Math.max(1, size))));
    }

    @RequiresFunction(AUDIT_VIEW)
    @GetMapping("/export")
    public PageVO<AuditEventVO> export(@RequestParam(required = false) Long departmentId,
                                       @RequestParam(required = false) Long applicationId,
                                       @RequestParam(required = false) String actor,
                                       @RequestParam(required = false) String action,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       HttpServletRequest req) {
        return AuditVoMapper.toAuditPageVO(audit.events(departmentId, applicationId, AdminAccess.of(req).visibility(),
                actor, action, keyword, from, to, 1, 5000));
    }

    @RequiresFunction(AUDIT_VIEW)
    @GetMapping("/verify")
    public AuditVerifyVO verify(@RequestParam(required = false) Long fromSeq,
                                @RequestParam(defaultValue = "5000") int limit,
                                HttpServletRequest req) {
        AdminAccess.of(req);
        return AuditVoMapper.toAuditVerifyVO(auditSeal.verify(fromSeq, Math.min(50_000, Math.max(1, limit))));
    }
}
