-- Issue #285: the release explorer orders a range's games by their Gameómetro community mean
-- before pagination, which PostgreSQL can only do over a Catalogue-owned column (ADR-0029).
-- Expand-only: a new table that older application versions never read.
--
-- Ratings owns every rating and its summary. It replaces a game's row through a Catalogue
-- application contract inside the transaction that changes the game's ratings, and a game without
-- ratings has no row, so a missing score is never stored as zero. The mean is the one decimal
-- Gameómetro shows. No migration reads Ratings tables: the application backfills games rated
-- before this version in bounded batches at startup, through the same contract.
CREATE TABLE catalogue.game_community_score (
    game_id uuid PRIMARY KEY,
    mean numeric(3, 1) NOT NULL,
    rating_count integer NOT NULL,
    CONSTRAINT fk_game_community_score_game
        FOREIGN KEY (game_id) REFERENCES catalogue.game (game_id),
    CONSTRAINT ck_game_community_score_mean CHECK (mean BETWEEN 1 AND 10),
    CONSTRAINT ck_game_community_score_count CHECK (rating_count > 0)
);

COMMENT ON TABLE catalogue.game_community_score IS
    'Projection of each rated Game''s Gameómetro community mean for the release order; Ratings owns the source.';

-- Hypes now also order the release explorer; the evidence itself is unchanged.
COMMENT ON TABLE catalogue.game_featured_evidence IS
    'Current normalized first-release, distinct accepted product and attention evidence; ranks featured releases and orders the release explorer by Hype.';
