-- Summary text is publication-owned. The default explicitly states missing curation;
-- it is product editorial copy, never fabricated provider content.
ALTER TABLE catalogue.game_snapshot
    ADD COLUMN summary_kind varchar(16) NOT NULL DEFAULT 'editorial',
    ADD COLUMN summary_text varchar(10000) NOT NULL DEFAULT 'Todavía no hay un resumen curado para este juego.',
    ADD COLUMN summary_language varchar(35) NOT NULL DEFAULT 'es',
    ADD COLUMN summary_source_kind varchar(32),
    ADD COLUMN summary_source_name varchar(200),
    ADD COLUMN summary_source_entity_type varchar(100),
    ADD CONSTRAINT ck_game_summary_text CHECK (btrim(summary_text) <> ''),
    ADD CONSTRAINT ck_game_summary_language CHECK (summary_language ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'),
    ADD CONSTRAINT ck_game_summary_variant CHECK (
        (summary_kind = 'editorial' AND summary_source_kind IS NULL
            AND summary_source_name IS NULL AND summary_source_entity_type IS NULL)
        OR (summary_kind = 'sourced'
            AND summary_source_kind IS NOT NULL
            AND summary_source_kind IN ('external_provider', 'product_curated', 'official_source')
            AND summary_source_name IS NOT NULL AND btrim(summary_source_name) <> ''
            AND summary_source_entity_type IS NOT NULL AND btrim(summary_source_entity_type) <> '')
    );

-- Minimal active-rating source for the public aggregate read. Authentication and
-- write commands are delivered separately; no rating can be written through this slice.
CREATE SCHEMA ratings;
REVOKE ALL ON SCHEMA ratings FROM PUBLIC;
GRANT USAGE ON SCHEMA ratings TO videogame_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA ratings
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO videogame_app;

CREATE TABLE ratings.rating (
    user_id uuid NOT NULL,
    game_id uuid NOT NULL REFERENCES catalogue.game(game_id),
    value smallint NOT NULL CHECK (value BETWEEN 1 AND 10),
    PRIMARY KEY (user_id, game_id)
);
CREATE INDEX ix_rating_game_value ON ratings.rating(game_id, value);
