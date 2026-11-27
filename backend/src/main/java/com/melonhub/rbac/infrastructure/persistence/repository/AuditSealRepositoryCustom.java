package com.melonhub.rbac.infrastructure.persistence.repository;

/** 审计链封存单行的强制刷新读与悲观锁读片段。 */
public interface AuditSealRepositoryCustom {
    record SealSnapshot(long lastId, long lastSeq, String lastHash, long anchorSeq,
                        String anchorHash, long v2FromSeq) {}

    SealSnapshot lockSeal();

    SealSnapshot readSeal();
}
