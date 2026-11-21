CREATE TABLE catalogue.game_library_entry (
    user_id uuid NOT NULL,
    game_id uuid NOT NULL REFERENCES catalogue.game (game_id),
    wished_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, game_id)
);

COMMENT ON TABLE catalogue.game_library_entry IS
    'Personal: one entry per product UserId and catalogue Game. The initial library state is wished.';
