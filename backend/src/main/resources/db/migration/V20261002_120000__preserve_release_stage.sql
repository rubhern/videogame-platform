-- Expand-only: old code omits this field; existing and old-version inserts stay UNKNOWN.
-- PostgreSQL applies the constant default without rewriting the table. No historical inference.
ALTER TABLE catalogue.release_snapshot
    ADD COLUMN release_stage varchar(20) NOT NULL DEFAULT 'unknown'
    CHECK (release_stage IN ('full_release', 'early_access', 'advance_access', 'beta', 'alpha', 'unknown'));
COMMENT ON COLUMN catalogue.release_snapshot.release_stage IS
    'Normalized product stage; independent of lifecycle. Unknown until supported provider evidence arrives.';

-- Repair pages keyset on release_id. Keep known stages out of repeated residual scans.
CREATE INDEX ix_release_stage_repair_unknown ON catalogue.release_snapshot (release_id)
    WHERE release_stage = 'unknown' AND source_kind = 'external_provider';
