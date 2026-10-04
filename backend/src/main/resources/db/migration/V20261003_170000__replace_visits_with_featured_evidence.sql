-- #151 correction: Visits has no independent consumer. Do not reinterpret its values as Hypes.
-- Reacquisition is required; ordinary catalogue/release data and media remain untouched.
DROP TABLE catalogue.game_popularity;

CREATE TABLE catalogue.game_featured_evidence (
    game_id uuid PRIMARY KEY REFERENCES catalogue.game (game_id),
    hypes bigint,
    first_release_date date CHECK (first_release_date IS NULL OR extract(year FROM first_release_date) BETWEEN 1 AND 9999),
    eligible_product boolean NOT NULL,
    source_name varchar(100) NOT NULL CHECK (btrim(source_name) <> ''),
    observed_at timestamptz NOT NULL,
    CONSTRAINT ck_featured_hypes_positive CHECK (hypes IS NULL OR hypes > 0)
);
COMMENT ON TABLE catalogue.game_featured_evidence IS
    'Current normalized first-release, distinct accepted product and attention evidence; featured only.';
