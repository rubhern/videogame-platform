-- Issue #151: provider-observed popularity evidence that ranks a calendar month's featured
-- releases. Expand-only: a new table that older application versions never read.
--
-- One current signal per Game: the IGDB Visits value, the provider's own calculation time and the
-- last time synchronization observed it. It measures attention, never quality, a rating or an
-- editorial judgement, and it never identifies anything. A Game without a usable signal has no row:
-- no value is ever invented. Synchronization writes it in the Game's own transaction, so a failed
-- or rolled-back Game keeps its last valid signal, and no history is retained.
CREATE TABLE catalogue.game_popularity (
    game_id uuid PRIMARY KEY,
    visits numeric NOT NULL,
    source_name varchar(100) NOT NULL,
    provider_calculated_at timestamptz,
    observed_at timestamptz NOT NULL,
    CONSTRAINT fk_game_popularity_game
        FOREIGN KEY (game_id)
        REFERENCES catalogue.game (game_id),
    CONSTRAINT ck_game_popularity_visits_positive
        CHECK (visits > 0),
    CONSTRAINT ck_game_popularity_source_name_not_blank
        CHECK (btrim(source_name) <> '')
);

COMMENT ON TABLE catalogue.game_popularity IS
    'Current provider-observed popularity per Game; ranks featured releases, never quality.';
