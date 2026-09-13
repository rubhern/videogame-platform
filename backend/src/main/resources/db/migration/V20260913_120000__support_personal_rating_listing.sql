-- Rebuildable Ratings-owned public game context. Catalogue remains the authority;
-- application contracts populate this projection, never cross-module SQL reads.
CREATE TABLE ratings.game_listing (
    game_id uuid PRIMARY KEY,
    slug varchar(300) NOT NULL,
    canonical_title varchar(300) NOT NULL,
    normalized_title text NOT NULL,
    title_search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('simple'::regconfig, normalized_title)) STORED,
    cover_kind varchar(20) NOT NULL,
    cover_reference text,
    cover_alternative_text text,
    cover_attribution text,
    cover_source_url text,
    CONSTRAINT ck_listing_cover_kind CHECK (cover_kind IN ('provider', 'product', 'unavailable'))
);
CREATE TABLE ratings.game_listing_alias (
    game_id uuid NOT NULL REFERENCES ratings.game_listing(game_id) ON DELETE CASCADE,
    normalized_alias text NOT NULL,
    search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('simple'::regconfig, normalized_alias)) STORED,
    PRIMARY KEY (game_id, normalized_alias)
);
CREATE INDEX ix_rating_user_updated_game
    ON ratings.rating (user_id, updated_at DESC, game_id);
