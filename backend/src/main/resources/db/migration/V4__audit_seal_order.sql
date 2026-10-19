-- Audit rows are chained in the order they are sealed, not in id order: a transaction that commits after a
-- higher id was already sealed still joins the chain at the end. Rows sealed before this migration were
-- chained in id order, so their position is their id.
ALTER TABLE audit_events
 ADD COLUMN seal_seq BIGINT NULL,
 ADD UNIQUE INDEX uk_audit_events_seal_seq (seal_seq);
UPDATE audit_events SET seal_seq=id WHERE chain_hash IS NOT NULL;

-- last_seq/last_hash describe the chain end, so removing the newest rows is detected. anchor_* is the last
-- link removed by retention cleanup, which lets verification check the first remaining row.
ALTER TABLE audit_seal
 ADD COLUMN last_seq BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN anchor_seq BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN anchor_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000';
UPDATE audit_seal SET last_seq=last_id WHERE id=1;
