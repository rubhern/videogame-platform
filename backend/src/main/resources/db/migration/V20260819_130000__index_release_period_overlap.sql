CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

CREATE INDEX ix_release_browse_recent_period
    ON catalogue.release_snapshot
    USING gist (
        publication_id,
        daterange(period_start, period_end, '[]')
    )
    WHERE release_status = 'released' AND period_start IS NOT NULL;

CREATE INDEX ix_release_browse_upcoming_period
    ON catalogue.release_snapshot
    USING gist (
        publication_id,
        daterange(period_start, period_end, '[]')
    )
    WHERE release_status NOT IN ('released', 'cancelled') AND period_start IS NOT NULL;

CREATE INDEX ix_release_browse_upcoming_unknown
    ON catalogue.release_snapshot (publication_id, game_id, release_id)
    INCLUDE (platform_id, region_id)
    WHERE release_status NOT IN ('released', 'cancelled') AND date_precision = 'unknown';
