-- Issue #386 (ADR-0034): TOPómetro ranks released games by one source of player ratings at a
-- time, ordering by the unrounded mean before pagination. Expand-only: a new nullable column and
-- two partial indexes that older application versions never read.
--
-- The community score projection (ADR-0029) kept only the one decimal Gameómetro shows; the
-- ranking orders by the unrounded mean of one contribution per rating (AGG-004), at the eighteen
-- places Ratings compares means at, so the projection gains it. It is NULL only for a row
-- projected before this version: the application's startup backfill re-projects every such row
-- through the same Ratings contract and per-game lock, and the ranking skips a row until then.
-- No migration reads Ratings tables. An older version writing during a rollout updates the shown
-- mean and count but not the exact mean; the next write for that game corrects it, as ADR-0029
-- already accepts for the projection itself.
ALTER TABLE catalogue.game_community_score
    ADD COLUMN exact_mean numeric(20, 18),
    ADD CONSTRAINT ck_game_community_score_exact_mean CHECK (exact_mean BETWEEN 1 AND 10);

COMMENT ON TABLE catalogue.game_community_score IS
    'Projection of each rated Game''s Gameómetro community score for the release order and TOPómetro; Ratings owns the source.';
COMMENT ON COLUMN catalogue.game_community_score.mean IS
    'Community mean at the one decimal Gameómetro shows.';
COMMENT ON COLUMN catalogue.game_community_score.exact_mean IS
    'Unrounded community mean at eighteen places, the ranking key; NULL until the startup backfill projects a row written before #386.';

-- Each ranking walks one source's scores in its order — the unrounded mean, the count, then the
-- unique game id — and stops once the requested page is complete.
CREATE INDEX ix_game_community_score_rank
    ON catalogue.game_community_score (exact_mean DESC, rating_count DESC, game_id)
    WHERE exact_mean IS NOT NULL;

CREATE INDEX ix_game_external_score_player_rank
    ON catalogue.game_external_score (score DESC, score_count DESC, game_id)
    WHERE source_name = 'IGDB' AND score_kind = 'player' AND score IS NOT NULL;
