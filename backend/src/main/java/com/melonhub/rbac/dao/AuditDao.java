package com.melonhub.rbac.dao;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tamper-evident audit chain. Rows are written without a hash first; a scheduled sealer locks the seal row, gives
 * every unsealed row the next sequence number in id order and fills prev_hash/chain_hash, so a row committed late
 * is appended instead of skipped. Verification walks the chain by sequence number.
 */
@Repository
public class AuditDao {
    public static final String GENESIS = "0".repeat(64);
    public record SealRow(long lastId, long lastSeq, String lastHash, long anchorSeq, String anchorHash, long v2FromSeq) {}
    public record Link(long seq, String hash) {}

    private final JdbcTemplate db;
    public AuditDao(JdbcTemplate db) { this.db = db; }

    private static final String SEAL = "SELECT last_id,last_seq,last_hash,anchor_seq,anchor_hash,v2_from_seq FROM audit_seal WHERE id=1";
    private static SealRow sealRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SealRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getLong(6));
    }
    public SealRow lockSeal() { return db.query(SEAL + " FOR UPDATE", (rs, i) -> sealRow(rs)).get(0); }
    public SealRow seal() { return db.query(SEAL, (rs, i) -> sealRow(rs)).get(0); }

    private static final String COLUMNS = "id,actor,action,target,department_id,application_id,detail,source_ip,UNIX_TIMESTAMP(created_at) AS ts";
    public List<Map<String,Object>> unsealed(int limit) {
        return db.queryForList("SELECT " + COLUMNS + " FROM audit_events WHERE seal_seq IS NULL ORDER BY id LIMIT ?", limit);
    }
    public int sealEvent(long id, long seq, String prev, String hash) {
        return db.update("UPDATE audit_events SET seal_seq=?,prev_hash=?,chain_hash=? WHERE id=? AND seal_seq IS NULL", seq, prev, hash, id);
    }
    public void updateSeal(long lastId, long lastSeq, String lastHash, long sealed) {
        db.update("UPDATE audit_seal SET last_id=?,last_seq=?,last_hash=?,sealed_count=sealed_count+?,updated_at=CURRENT_TIMESTAMP WHERE id=1",
                lastId, lastSeq, lastHash, sealed);
    }

    /** Sealed rows in chain order, never beyond the chain end read together with them. */
    public List<Map<String,Object>> sealedFrom(long fromSeq, long toSeq, int limit) {
        return db.queryForList("SELECT " + COLUMNS + ",seal_seq,prev_hash,chain_hash FROM audit_events WHERE seal_seq>=? AND seal_seq<=? "
                + "ORDER BY seal_seq LIMIT ?", fromSeq, toSeq, limit);
    }
    /** The link right before a position; empty when every earlier row was removed. */
    public Optional<Link> linkBefore(long seq) {
        return db.query("SELECT seal_seq,chain_hash FROM audit_events WHERE seal_seq<? ORDER BY seal_seq DESC LIMIT 1",
                (rs, i) -> new Link(rs.getLong(1), rs.getString(2)), seq).stream().findFirst();
    }

    // ---- retention ----
    /** First position, in chain order, of a row that is still inside the retention period. */
    public Optional<Long> firstSeqSince(int days) {
        return db.queryForList("SELECT seal_seq FROM audit_events WHERE seal_seq IS NOT NULL AND created_at>=TIMESTAMPADD(DAY,-?,CURRENT_TIMESTAMP) "
                + "ORDER BY seal_seq LIMIT 1", Long.class, days).stream().findFirst();
    }
    /** The last link of the oldest batch below a position: the chain is only ever cut as a prefix. */
    public Optional<Link> prefixEnd(long belowSeq, int limit) {
        return db.query("SELECT seal_seq,chain_hash FROM audit_events WHERE seal_seq=(SELECT MAX(seal_seq) FROM "
                + "(SELECT seal_seq FROM audit_events WHERE seal_seq<? ORDER BY seal_seq LIMIT ?) batch)",
                (rs, i) -> new Link(rs.getLong(1), rs.getString(2)), belowSeq, limit).stream().findFirst();
    }
    public int deleteThrough(long seq) { return db.update("DELETE FROM audit_events WHERE seal_seq<=?", seq); }
    public void updateAnchor(Link anchor) {
        db.update("UPDATE audit_seal SET anchor_seq=?,anchor_hash=? WHERE id=1", anchor.seq(), anchor.hash());
    }

    public Map<String,Object> sealState() {
        return db.queryForMap("SELECT s.last_id AS lastId,s.last_seq AS lastSeq,s.last_hash AS lastHash,s.sealed_count AS sealedCount,"
                + "s.anchor_seq AS anchorSeq,s.updated_at AS updatedAt,"
                + "(SELECT COUNT(*) FROM audit_events WHERE seal_seq IS NULL) AS unsealed,"
                + "(SELECT COUNT(*) FROM audit_events) AS total FROM audit_seal s WHERE s.id=1");
    }

    /**
     * Original canonical form, kept for rows sealed before V5. Fields are joined with "|", which text fields may
     * contain, so it is only used to verify those rows.
     */
    public static String canonicalV1(Map<String,Object> row) {
        return row.get("id") + "|" + row.get("actor") + "|" + row.get("action") + "|" + row.get("target") + "|"
                + value(row.get("department_id")) + "|" + value(row.get("application_id")) + "|" + row.get("detail") + "|"
                + row.get("source_ip") + "|" + row.get("ts");
    }
    /**
     * Unambiguous canonical form: every field is written as its length, ":" and the value ("-" for NULL), and the
     * chain position comes first, so no change of field boundaries or order yields the same text. Timestamps use
     * epoch seconds to avoid format drift.
     */
    public static String canonicalV2(long seq, Map<String,Object> row) {
        var text = new StringBuilder("v2");
        for (Object field : new Object[] { seq, row.get("id"), row.get("actor"), row.get("action"), row.get("target"), row.get("department_id"),
                row.get("application_id"), row.get("detail"), row.get("source_ip"), row.get("ts") }) {
            if (field == null) { text.append("|-"); continue; }
            String value = field.toString();
            text.append('|').append(value.length()).append(':').append(value);
        }
        return text.toString();
    }
    /** Rows at or after v2FromSeq use the v2 form; earlier rows were sealed with v1. */
    public static String chainHash(String prevHash, long seq, Map<String,Object> row, long v2FromSeq) {
        return sha256(prevHash + "|" + (seq >= v2FromSeq ? canonicalV2(seq, row) : canonicalV1(row)));
    }

    private static String value(Object column) { return column == null ? "" : column.toString(); }
    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
