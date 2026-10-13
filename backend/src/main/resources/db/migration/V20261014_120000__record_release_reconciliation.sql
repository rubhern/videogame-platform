-- #267: last successful provider reconciliation of a release, kept apart from its snapshot.
-- release_snapshot.last_synchronized_at advances only when published values change (ADR-0017),
-- and provider_updated_at is the provider's own claim. This row records that a committed Game
-- reconciliation re-checked a provider-owned release, so an identical snapshot is never rewritten
-- and the catalogue revision never rotates for a check. No backfill: an absent row means no
-- check was recorded, never an invented one.
CREATE TABLE catalogue.release_reconciliation (
    release_id uuid NOT NULL,
    provider varchar(100) NOT NULL,
    last_reconciled_at timestamptz NOT NULL,
    PRIMARY KEY (release_id, provider),
    -- The check belongs to the provider reference it re-read; removing the reference removes it.
    CONSTRAINT fk_release_reconciliation_reference
        FOREIGN KEY (provider, release_id)
        REFERENCES catalogue.release_external_reference (provider, release_id)
        ON DELETE CASCADE
);
COMMENT ON TABLE catalogue.release_reconciliation IS
    'Operational evidence: last committed provider reconciliation per release; not product content.';

-- Appends the check to the private Grafana read contract; existing columns keep their meaning.
CREATE OR REPLACE VIEW catalogue.observability_release_quality AS
SELECT s.release_status AS provider_status, s.date_precision, s.verification_level,
       s.review_status, s.last_synchronized_at, s.last_verified_at,
       (SELECT max(r.last_reconciled_at) FROM catalogue.release_reconciliation r
        WHERE r.release_id = s.release_id) AS last_reconciled_at
FROM catalogue.release_snapshot s;
COMMENT ON VIEW catalogue.observability_release_quality IS
    'Current release evidence, not clock-derived effective lifecycle status; no record identities. last_synchronized_at is the last published change; last_reconciled_at the last committed check, null when none was recorded.';
