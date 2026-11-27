package com.melonhub.rbac.infrastructure.persistence.repository;

import com.melonhub.rbac.infrastructure.persistence.entity.AuditSeal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AuditSealRepositoryImpl implements AuditSealRepositoryCustom {
    private final EntityManager em;

    AuditSealRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public SealSnapshot lockSeal() {
        AuditSeal seal = em.find(AuditSeal.class, 1);
        em.refresh(seal, LockModeType.PESSIMISTIC_WRITE);
        return toSnapshot(seal);
    }

    @Override
    public SealSnapshot readSeal() {
        AuditSeal seal = em.find(AuditSeal.class, 1);
        em.refresh(seal);
        return toSnapshot(seal);
    }

    private static SealSnapshot toSnapshot(AuditSeal seal) {
        return new SealSnapshot(seal.getLastId(), seal.getLastSeq(), seal.getLastHash(),
                seal.getAnchorSeq(), seal.getAnchorHash(), seal.getV2FromSeq());
    }
}
