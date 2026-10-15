-- Issue #245: durable coverage of release-date intervals that a synchronization run scanned
-- completely. Operational acquisition evidence, never catalogue content or a correctness claim.
--
-- Each row is one disjoint inclusive interval and the latest complete scan that covered it. A newer
-- complete scan trims or splits the intervals it overlaps and takes their place, so the row count is
-- bounded by the days ever covered, never by the number of runs. Absent days were never covered.
-- failed_games is the covering run's own count of Games that kept their last valid state; failed
-- Games have no known date, so it describes the whole run, not this interval alone.
-- run_id is a correlation only: synchronization_run is pruned history, so there is no foreign key
-- and pruning never removes coverage.
CREATE TABLE catalogue.synchronization_coverage (
    provider varchar(100) NOT NULL CHECK (btrim(provider) <> ''),
    covered_from date NOT NULL,
    covered_to date NOT NULL,
    run_id uuid NOT NULL,
    scan_started_at timestamptz NOT NULL,
    scan_completed_at timestamptz NOT NULL,
    failed_games bigint NOT NULL,
    CONSTRAINT pk_synchronization_coverage PRIMARY KEY (provider, covered_from),
    CONSTRAINT ck_synchronization_coverage_interval CHECK (covered_from <= covered_to),
    CONSTRAINT ck_synchronization_coverage_scan CHECK (scan_completed_at >= scan_started_at),
    CONSTRAINT ck_synchronization_coverage_failed_games CHECK (failed_games >= 0),
    -- Deferred to commit: replacing an overlapped interval briefly overlaps its own remainder
    -- inside the one transaction that also completes the run.
    CONSTRAINT ex_synchronization_coverage_disjoint EXCLUDE USING gist (
        provider WITH =,
        daterange(covered_from, covered_to, '[]') WITH &&
    ) DEFERRABLE INITIALLY DEFERRED
);
COMMENT ON TABLE catalogue.synchronization_coverage IS
    'Disjoint release-date intervals completely scanned by a run; not pruned with runs.';
