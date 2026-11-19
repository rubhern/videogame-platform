CREATE TABLE catalogue.game_detail_visit (
    visit_id uuid PRIMARY KEY,
    game_id uuid NOT NULL REFERENCES catalogue.game (game_id) ON DELETE CASCADE,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX ix_game_detail_visit_time
    ON catalogue.game_detail_visit (occurred_at, visit_id) INCLUDE (game_id);
CREATE INDEX ix_rating_creation_time
    ON ratings.rating (created_at, game_id);

GRANT SELECT, INSERT, DELETE ON TABLE catalogue.game_detail_visit TO videogame_app;
