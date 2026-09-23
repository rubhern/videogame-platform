-- Issue #177: temporal classification must not depend on the last synchronization.
--
-- release_status now stores only provider/curator evidence that cannot be derived from the release
-- date and the current evaluation date:
--   * 'cancelled' / 'delayed' : the trusted provider signal;
--   * 'released'              : explicit evidence that the release occurred, reserved for curated or
--                              unknown-date facts (synchronization never writes it);
--   * 'announced'            : no negative signal; whether a known date has occurred is derived per
--                              request (SCHEDULED vs RELEASED) and never persisted.
--
-- Legacy 'scheduled' and 'unknown' were only ever written by the clock-at-synchronization rule and
-- carry no evidence beyond the release date, so they collapse to 'announced'. Legacy 'released' rows
-- were derived from already-past dates by the sole IGDB writer, so they remain consistent with the
-- effective RELEASED status derived from their date.
UPDATE catalogue.release_snapshot
SET release_status = 'announced'
WHERE release_status IN ('scheduled', 'unknown');

-- Recent and upcoming now share the same period-overlap access path: both classify by the effective
-- date against the window, differing only by residual filters (recent also excludes delayed; each
-- view applies its own occurred/not-occurred bound). A single GiST period index therefore serves
-- both views, and the previous per-view period indexes (keyed on the clock-derived status) are
-- dropped as redundant. A cancelled release is never browsable, so the partial predicates exclude
-- it to keep the indexes small.
DROP INDEX IF EXISTS catalogue.ix_release_browse_recent_period;
DROP INDEX IF EXISTS catalogue.ix_release_browse_upcoming_period;
DROP INDEX IF EXISTS catalogue.ix_release_browse_upcoming_unknown;

CREATE INDEX ix_release_browse_period
    ON catalogue.release_snapshot
    USING gist (
        publication_id,
        daterange(period_start, period_end, '[]')
    )
    WHERE release_status <> 'cancelled' AND period_start IS NOT NULL;

-- The TBA branch shows unknown dates that are neither cancelled nor explicitly released, so the
-- partial predicate matches it exactly.
CREATE INDEX ix_release_browse_unknown
    ON catalogue.release_snapshot (publication_id, game_id, release_id)
    INCLUDE (platform_id, region_id)
    WHERE release_status NOT IN ('cancelled', 'released') AND date_precision = 'unknown';

-- Tighten the persisted domain to the evidence values only.
ALTER TABLE catalogue.release_snapshot
    DROP CONSTRAINT ck_release_snapshot_status;
ALTER TABLE catalogue.release_snapshot
    ADD CONSTRAINT ck_release_snapshot_status
        CHECK (release_status IN ('announced', 'released', 'delayed', 'cancelled'));
