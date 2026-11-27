package com.melonhub.rbac.api.audit.vo;

/**
 * 审计哈希链完整性校验结果 VO。
 */
public record AuditVerifyVO(
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
        AuditSealStateVO seal
) {}
