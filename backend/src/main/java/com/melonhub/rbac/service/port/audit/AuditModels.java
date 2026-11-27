package com.melonhub.rbac.service.port.audit;

import java.time.Instant;

/**
 * 审计日志查询、哈希链封存状态与链路完整性校验结果模型。
 */
public final class AuditModels {
    private AuditModels() {}

    public record AuditEventResult(
            long id,
            String actor,
            String action,
            String target,
            String detail,
            String sourceIp,
            Instant createdAt,
            Long departmentId,
            String departmentName,
            Long applicationId,
            String applicationName
    ) {}

    public record AuditSealStateResult(
            long lastId,
            long lastSeq,
            String lastHash,
            long sealedCount,
            long anchorSeq,
            Instant updatedAt,
            long unsealed,
            long total
    ) {}

    public record AuditVerifyResult(
            boolean ok,
            String problem,
            long checked,
            Long firstSeq,
            Long lastSeq,
            Long firstId,
            Long lastId,
            Long brokenAt,
            Long brokenSeq,
            boolean complete,
            Long nextSeq,
            AuditSealStateResult seal
    ) {}
}
