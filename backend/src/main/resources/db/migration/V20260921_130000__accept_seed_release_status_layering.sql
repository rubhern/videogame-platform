-- Issue #177 follow-up: keep the persisted release_status domain compatible with development-seed
-- layering.
--
-- V20260921_120000 narrowed release_status to provider-evidence values. That works when Flyway
-- applies migrations and the development seed together (the seed's legacy 'scheduled'/'unknown'
-- rows are normalised before the CHECK tightens), but the packaged runtime seeds differently:
-- scripts/validate-container-image.sh migrates the production image (db/migration only) and then
-- pipes each db/dev-seed file straight into psql, so those inserts meet the already-tightened CHECK
-- and fail. The persisted vocabulary is not an invariant on its own — synchronization writes only
-- announced/delayed/cancelled and the effective scheduled/released state for a known date is derived
-- per request (see EffectiveReleaseStatusPolicy) — so the column simply needs to accept the seed's
-- historical evidence values again. Restore the original domain.
ALTER TABLE catalogue.release_snapshot
    DROP CONSTRAINT ck_release_snapshot_status;
ALTER TABLE catalogue.release_snapshot
    ADD CONSTRAINT ck_release_snapshot_status
        CHECK (release_status IN
            ('announced', 'scheduled', 'released', 'delayed', 'cancelled', 'unknown'));
