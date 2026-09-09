-- UC-009: current Game state, a lightweight catalogue revision, and operational runs.
-- The approved product taxonomy is required without the disposable demonstration Games.
-- Preserve existing code-to-ID mappings when upgrading a seeded database.
INSERT INTO catalogue.platform (platform_id, code, display_name) VALUES
    ('10000000-0000-4000-8000-000000000001', 'playstation-5', 'PlayStation 5'),
    ('10000000-0000-4000-8000-000000000002', 'nintendo-switch-2', 'Nintendo Switch 2'),
    ('10000000-0000-4000-8000-000000000003', 'windows-pc', 'Windows PC'),
    ('10000000-0000-4000-8000-000000000004', 'xbox-series', 'Xbox Series X|S')
ON CONFLICT DO NOTHING;
INSERT INTO catalogue.region (region_id, code, display_name) VALUES
    ('20000000-0000-4000-8000-000000000001', 'worldwide', 'Worldwide'),
    ('20000000-0000-4000-8000-000000000002', 'europe', 'Europe'),
    ('20000000-0000-4000-8000-000000000003', 'unknown', 'Unknown'),
    ('20000000-0000-4000-8000-000000000004', 'north-america', 'North America'),
    ('20000000-0000-4000-8000-000000000005', 'japan', 'Japan')
ON CONFLICT DO NOTHING;

-- Retain the visible state from the previously supported global-publication model.
DELETE FROM catalogue.release_snapshot WHERE publication_id NOT IN
    (SELECT publication_id FROM catalogue.catalogue_publication WHERE is_current);
DELETE FROM catalogue.game_alias WHERE publication_id NOT IN
    (SELECT publication_id FROM catalogue.catalogue_publication WHERE is_current);
DELETE FROM catalogue.game_snapshot WHERE publication_id NOT IN
    (SELECT publication_id FROM catalogue.catalogue_publication WHERE is_current);
DELETE FROM catalogue.catalogue_publication WHERE NOT is_current;

-- The legacy table/foreign-key names remain for compatible public reads and seeds.
-- There is at most one metadata row; its ID never rotates and its version is a validator.
CREATE UNIQUE INDEX uq_catalogue_revision_singleton ON catalogue.catalogue_publication ((true));
ALTER TABLE catalogue.game_snapshot ADD CONSTRAINT uq_current_game UNIQUE (game_id);
ALTER TABLE catalogue.release_snapshot ADD CONSTRAINT uq_current_release UNIQUE (release_id);
COMMENT ON TABLE catalogue.catalogue_publication IS
    'Singleton current catalogue revision for read validators, never a catalogue history.';
COMMENT ON TABLE catalogue.game_snapshot IS 'One current, valid state per Game.';
CREATE TABLE catalogue.release_external_reference (
    provider varchar(100) NOT NULL CHECK (btrim(provider) <> ''),
    provider_id varchar(200) NOT NULL CHECK (btrim(provider_id) <> ''),
    release_id uuid NOT NULL,
    game_id uuid NOT NULL,
    PRIMARY KEY (provider, provider_id),
    UNIQUE (provider, release_id),
    FOREIGN KEY (release_id, game_id) REFERENCES catalogue.game_release (release_id, game_id)
);
CREATE INDEX ix_release_reference_game ON catalogue.release_external_reference (game_id);

CREATE TABLE catalogue.synchronization_run (
    run_id uuid PRIMARY KEY,
    provider varchar(100) NOT NULL CHECK (btrim(provider) <> ''),
    window_from date NOT NULL,
    window_to date NOT NULL,
    started_at timestamptz NOT NULL,
    heartbeat_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamptz,
    run_status varchar(16) NOT NULL CHECK (run_status IN ('running','succeeded','partial','failed','abandoned')),
    outcome_code varchar(64),
    report jsonb,
    CHECK (window_from <= window_to),
    CHECK ((run_status = 'running' AND completed_at IS NULL AND outcome_code IS NULL)
        OR (run_status <> 'running' AND completed_at IS NOT NULL
            AND completed_at >= started_at AND outcome_code IS NOT NULL))
);
CREATE UNIQUE INDEX uq_synchronization_run_active ON catalogue.synchronization_run (provider)
    WHERE run_status = 'running';
CREATE INDEX ix_synchronization_run_recent ON catalogue.synchronization_run
    (provider, started_at DESC, run_id DESC);
