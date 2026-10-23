-- One current operator target. Completed target replacement is explicit; unfinished work survives.
CREATE TABLE catalogue.historical_backfill (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    backfill_id uuid NOT NULL UNIQUE,
    execution_id uuid NOT NULL,
    window_from date NOT NULL CHECK (window_from >= DATE '1950-01-01'),
    window_to date NOT NULL CHECK (window_to <= DATE '2099-12-31'),
    covered_months varchar(8) NOT NULL CHECK (covered_months IN ('skip','rerun')),
    state varchar(16) NOT NULL CHECK (state IN ('running','waiting','stopping','stopped','interrupted','completed','attention')),
    next_from date NOT NULL,
    current_to date,
    -- Correlation only: ordinary run history can be pruned.
    run_id uuid,
    completed_months integer NOT NULL DEFAULT 0 CHECK (completed_months >= 0),
    skipped_months integer NOT NULL DEFAULT 0 CHECK (skipped_months BETWEEN 0 AND completed_months),
    processed_games bigint NOT NULL DEFAULT 0 CHECK (processed_games >= 0),
    processed_releases bigint NOT NULL DEFAULT 0 CHECK (processed_releases >= 0),
    quality_failures bigint NOT NULL DEFAULT 0 CHECK (quality_failures >= 0),
    current_games bigint,
    current_releases bigint,
    current_failures bigint,
    current_quality_failures bigint,
    current_provider_requests bigint,
    started_at timestamptz NOT NULL,
    activity_at timestamptz NOT NULL,
    finished_at timestamptz,
    last_reason varchar(100),
    CHECK (window_from <= window_to),
    CHECK (next_from BETWEEN window_from AND window_to + 1)
);
COMMENT ON TABLE catalogue.historical_backfill IS
    'Explicit sequential UC-009 target and month checkpoint, independent of pruned run history.';
