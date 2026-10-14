-- Issue #255 (phase 1): the current external player score of a Game, acquired with the Game by
-- catalogue synchronization (UC-009) and owned by Catalogue. Expand-only: a new table that older
-- application versions never read, and that no public read uses yet.
--
-- One row per Game, source and kind: the source's own player mean on its declared 0-100 scale and
-- the positive number of that source's ratings, attributed to the provider page of the work. A
-- row whose score and count are both absent records that the last successful check found no
-- usable score; no row means the Game was never checked. observed_at is the last successful
-- check and changed_at the last change of the score, its count or its presence, so an unchanged
-- re-check never looks like new content. provider_record_updated_at is the provider's claim about
-- its Game record, never the score's own update time. Provider identifiers stay in the existing
-- external references. No history is kept: this is current state, not a ledger. Only IGDB player
-- scores are approved (#256); critic and combined scores are never stored. No backfill: existing
-- Games gain a row when synchronization or repair next reconciles them.
CREATE TABLE catalogue.game_external_score (
    game_id uuid NOT NULL,
    source_name varchar(16) NOT NULL,
    score_kind varchar(16) NOT NULL,
    score numeric(9, 6),
    score_count bigint,
    source_url varchar(500),
    provider_record_updated_at timestamptz,
    observed_at timestamptz NOT NULL,
    changed_at timestamptz NOT NULL,
    CONSTRAINT pk_game_external_score PRIMARY KEY (game_id, source_name, score_kind),
    CONSTRAINT fk_game_external_score_game
        FOREIGN KEY (game_id) REFERENCES catalogue.game (game_id),
    CONSTRAINT ck_game_external_score_source CHECK (source_name = 'IGDB'),
    CONSTRAINT ck_game_external_score_kind CHECK (score_kind = 'player'),
    CONSTRAINT ck_game_external_score_pair CHECK ((score IS NULL) = (score_count IS NULL)),
    CONSTRAINT ck_game_external_score_scale CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_game_external_score_count CHECK (score_count > 0),
    CONSTRAINT ck_game_external_score_attribution CHECK (score IS NULL OR source_url IS NOT NULL),
    CONSTRAINT ck_game_external_score_source_url
        CHECK (source_url ~ '^https://www\.igdb\.com/games/[^/?#[:space:]]+$'),
    CONSTRAINT ck_game_external_score_change_order CHECK (changed_at <= observed_at)
);

COMMENT ON TABLE catalogue.game_external_score IS
    'Current external player score per Game, source and kind; not a Gameómetro rating and not a history.';
COMMENT ON COLUMN catalogue.game_external_score.score IS
    'Source player mean on its declared 0-100 scale; null with score_count when the source states none.';
COMMENT ON COLUMN catalogue.game_external_score.observed_at IS
    'Last successful check; age alone never invalidates the score.';
COMMENT ON COLUMN catalogue.game_external_score.changed_at IS
    'Last change of score, count or presence; an unchanged re-check keeps it.';
COMMENT ON COLUMN catalogue.game_external_score.provider_record_updated_at IS
    'Provider update time of the Game record, never of the score itself.';

-- Appends coverage to the private Grafana inventory; existing columns keep their meaning. A Game
-- without a row was never checked, which is different from a check that found no score.
CREATE OR REPLACE VIEW catalogue.observability_inventory AS
SELECT g.games, r.releases, g.provider_covers, g.fallback_covers, e.featured_evidence_games,
       s.player_score_checked_games, s.player_score_games
FROM (SELECT count(*) AS games,
             count(*) FILTER (WHERE cover_usage_mode = 'provider_cdn_reference') AS provider_covers,
             count(*) FILTER (WHERE cover_usage_mode = 'product_owned') AS fallback_covers
      FROM catalogue.game_snapshot) g
CROSS JOIN (SELECT count(*) AS releases FROM catalogue.release_snapshot) r
CROSS JOIN (SELECT count(*) AS featured_evidence_games FROM catalogue.game_featured_evidence) e
CROSS JOIN (SELECT count(*) AS player_score_checked_games,
                   count(score) AS player_score_games
            FROM catalogue.game_external_score WHERE score_kind = 'player') s;
