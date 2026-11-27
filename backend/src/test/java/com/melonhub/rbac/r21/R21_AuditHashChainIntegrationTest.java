package com.melonhub.rbac.r21;

import static org.assertj.core.api.Assertions.assertThat;

import com.melonhub.rbac.service.port.audit.AuditSealUseCase;
import com.melonhub.rbac.service.structured.audit.StructuredAuditSealService;
import com.melonhub.rbac.shared.GovernanceIntegrationSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 审计哈希链底层封存、晚提交入链、前缀保留清理与 v2 防挪移篡改专项测试。对应需求：R21（见 doc/requirements.md）。 */
class R21_AuditHashChainIntegrationTest extends GovernanceIntegrationSupport {
    // 统一测试参数区
    private static final int VERIFY_LIMIT = 10_000;
    private static final int RETENTION_DAYS = 180;
    private static final int EXPIRED_AGE_DAYS = 200;
    private static final String TAMPERED_DETAIL = "篡改内容";
    private static final String PROBLEM_MODIFIED = "MODIFIED";
    private static final String PROBLEM_TAIL_MISSING = "TAIL_MISSING";

    @DisplayName("R21 审计哈希链校验通过并可检出记录篡改")
    @Test void auditChainVerifiesAndDetectsTampering() {
        rbacAudit(0);
        long first = lastAuditId();
        rbacAudit(1);
        rbacAudit(2);
        sealer.sealNow();
        long from = sealSeq(first);
        var before = sealer.verify(from, VERIFY_LIMIT);
        assertThat(before.ok()).isTrue();
        assertThat(before.complete()).isTrue();
        long tampered = lastAuditId();
        db.update("UPDATE audit_events SET detail=? WHERE id=?", TAMPERED_DETAIL, tampered);
        var after = sealer.verify(from, VERIFY_LIMIT);
        assertThat(after.ok()).isFalse();
        assertThat(after.problem()).isEqualTo(PROBLEM_MODIFIED);
        assertThat(after.brokenAt()).isEqualTo(tampered);
    }

    @DisplayName("R21 晚提交小 ID 审计记录按封存序入链并检出链尾缺失")
    @Test void lateCommittedRowsJoinTheChainAndRemovedTailIsDetected() {
        sealer.sealNow();
        rbacAudit(0);
        long late = lastAuditId();
        rbacAudit(1);
        long sealedFirst = lastAuditId();
        // Stand in for a transaction that has not committed yet: its id exists, its row is not visible.
        var row = db.queryForMap("SELECT actor,action,target,detail,source_ip FROM audit_events WHERE id=?", late);
        db.update("DELETE FROM audit_events WHERE id=?", late);
        sealer.sealNow();
        db.update("INSERT INTO audit_events(id,actor,action,target,detail,source_ip) VALUES (?,?,?,?,?,?)",
                late, row.get("actor"), row.get("action"), row.get("target"), row.get("detail"), row.get("source_ip"));
        sealer.sealNow();
        long from = sealSeq(sealedFirst);
        assertThat(sealSeq(late)).as("late row is appended, not skipped").isEqualTo(from + 1);
        var whole = sealer.verify(from, VERIFY_LIMIT);
        assertThat(whole.ok()).isTrue();
        assertThat(whole.checked()).isEqualTo(2L);
        assertThat(whole.complete()).isTrue();
        // Removing the newest sealed row leaves an intact but shorter chain; the recorded chain end exposes it.
        db.update("DELETE FROM audit_events WHERE id=?", late);
        var cut = sealer.verify(from, VERIFY_LIMIT);
        assertThat(cut.ok()).isFalse();
        assertThat(cut.problem()).isEqualTo(PROBLEM_TAIL_MISSING);
        db.update("DELETE FROM audit_events WHERE seal_seq IS NOT NULL");
        var emptied = sealer.verify(null, VERIFY_LIMIT);
        assertThat(emptied.ok()).isFalse();
        assertThat(emptied.problem()).isEqualTo(PROBLEM_TAIL_MISSING);
    }

    @DisplayName("R21 审计保留期按前缀清理更新锚点后剩余链仍可验通")
    @Test void retentionCleanupCutsAPrefixAndLeavesAVerifiableChain() {
        rbacAudit(0);
        rbacAudit(1);
        long kept = lastAuditId();
        rbacAudit(2);
        long newest = lastAuditId();
        sealer.sealNow();
        long keptSeq = sealSeq(kept);
        db.update("UPDATE audit_events SET created_at=TIMESTAMPADD(DAY,-?,CURRENT_TIMESTAMP) WHERE seal_seq<?", EXPIRED_AGE_DAYS, keptSeq);
        while (sealer.purge(RETENTION_DAYS) == AuditSealUseCase.PURGE_BATCH) { /* drain */ }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE seal_seq<?", Long.class, keptSeq)).isZero();
        assertThat(db.queryForObject("SELECT anchor_seq FROM audit_seal WHERE id=1", Long.class)).isEqualTo(keptSeq - 1);
        var result = sealer.verify(null, VERIFY_LIMIT);
        assertThat(result.ok()).isTrue();
        assertThat(result.firstId()).isEqualTo(kept);
        // Even when everything is past retention the chain end is kept, so the end can still be checked.
        db.update("UPDATE audit_events SET created_at=TIMESTAMPADD(DAY,-?,CURRENT_TIMESTAMP) WHERE seal_seq IS NOT NULL", EXPIRED_AGE_DAYS);
        sealer.purge(RETENTION_DAYS);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE seal_seq IS NOT NULL", Long.class)).isEqualTo(1L);
        assertThat(db.queryForObject("SELECT seal_seq FROM audit_events WHERE id=?", Long.class, newest)).isNotNull();
    }

    @DisplayName("R21 v2 长度前缀规范化哈希可检出跨审计字段挪移文本")
    @Test void movingTextBetweenAuditFieldsIsDetected() {
        var original = new StructuredAuditSealService.ChainRow(1L, "left|mid", "right", "t", null, null, "", "", 0L, null, null, null);
        var shifted = new StructuredAuditSealService.ChainRow(1L, "left", "mid|right", "t", null, null, "", "", 0L, null, null, null);
        // The original "|"-joined form cannot tell these rows apart; the length-prefixed form can.
        assertThat(StructuredAuditSealService.canonicalV1(shifted)).isEqualTo(StructuredAuditSealService.canonicalV1(original));
        assertThat(StructuredAuditSealService.canonicalV2(1, shifted)).isNotEqualTo(StructuredAuditSealService.canonicalV2(1, original));
        rbacAudit(0);
        long id = lastAuditId();
        db.update("UPDATE audit_events SET actor='left|mid',action='right' WHERE id=?", id);
        sealer.sealNow();
        long from = sealSeq(id);
        assertThat(sealer.verify(from, VERIFY_LIMIT).ok()).isTrue();
        db.update("UPDATE audit_events SET actor='left',action='mid|right' WHERE id=?", id);
        var result = sealer.verify(from, VERIFY_LIMIT);
        assertThat(result.ok()).isFalse();
        assertThat(result.problem()).isEqualTo(PROBLEM_MODIFIED);
    }
}
