package com.melonhub.rbac.api.audit;

import com.melonhub.rbac.api.audit.vo.AuditEventVO;
import com.melonhub.rbac.api.audit.vo.AuditSealStateVO;
import com.melonhub.rbac.api.audit.vo.AuditVerifyVO;
import com.melonhub.rbac.api.shared.vo.PageVO;
import com.melonhub.rbac.service.port.audit.AuditModels.AuditEventResult;
import com.melonhub.rbac.service.port.audit.AuditModels.AuditSealStateResult;
import com.melonhub.rbac.service.port.audit.AuditModels.AuditVerifyResult;
import com.melonhub.rbac.service.port.common.PageResult;

/**
 * 审计日志与哈希链校验模块 Result 与 VO 映射器。
 */
public final class AuditVoMapper {
    private AuditVoMapper() {}

    public static PageVO<AuditEventVO> toAuditPageVO(PageResult<AuditEventResult> p) {
        return new PageVO<>(p.items().stream().map(e -> new AuditEventVO(
                e.id(), e.actor(), e.action(), e.target(), e.detail(), e.sourceIp(), e.createdAt(),
                e.departmentId(), e.departmentName(), e.applicationId(), e.applicationName())).toList(),
                p.total(), p.page(), p.size());
    }

    public static AuditSealStateVO toAuditSealStateVO(AuditSealStateResult s) {
        if (s == null) {
            return null;
        }
        return new AuditSealStateVO(s.lastId(), s.lastSeq(), s.lastHash(), s.sealedCount(), s.anchorSeq(),
                s.updatedAt(), s.unsealed(), s.total());
    }

    public static AuditVerifyVO toAuditVerifyVO(AuditVerifyResult r) {
        return new AuditVerifyVO(r.ok(), r.problem(), r.checked(), r.firstSeq(), r.lastSeq(),
                r.firstId(), r.lastId(), r.brokenAt(), r.brokenSeq(), r.complete(), r.nextSeq(),
                toAuditSealStateVO(r.seal()));
    }
}
