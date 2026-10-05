-- Extend the rebuildable Ratings listing, preserving ADR-0018 and older readers/writers.
-- Existing rows are backfilled through Catalogue application contracts in keyset startup batches.
ALTER TABLE ratings.game_listing ADD COLUMN genres_projected boolean NOT NULL DEFAULT false;
CREATE TABLE ratings.genre_label (
    genre_code varchar(100) PRIMARY KEY,
    display_name varchar(200) NOT NULL CHECK (btrim(display_name) <> '')
);
CREATE TABLE ratings.game_listing_genre (
    game_id uuid NOT NULL REFERENCES ratings.game_listing(game_id) ON DELETE CASCADE,
    genre_code varchar(100) NOT NULL REFERENCES ratings.genre_label(genre_code),
    position smallint NOT NULL CHECK (position BETWEEN 1 AND 50),
    PRIMARY KEY (game_id, genre_code),
    UNIQUE (game_id, position)
);
