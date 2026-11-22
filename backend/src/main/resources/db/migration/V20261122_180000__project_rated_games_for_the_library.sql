-- Catalogue's projection of which games each player has rated (#591), so Mis juegos can count
-- and complete the rated games that hold no state without reading Ratings tables (ADR-0051).
-- Ratings maintains it inside every rating write and backfills it at startup; no migration
-- reads Ratings tables (ADR-0029).
CREATE TABLE catalogue.library_rated_game (
    user_id uuid NOT NULL,
    game_id uuid NOT NULL REFERENCES catalogue.game (game_id),
    PRIMARY KEY (user_id, game_id)
);

COMMENT ON TABLE catalogue.library_rated_game IS
    'Personal: one row per product UserId and catalogue Game the user has rated. Derived from Ratings; erased with the library.';
