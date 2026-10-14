-- Issue #255 (phase 1): observability of acquired IGDB player scores. V20261014_150000 is already
-- applied to local databases, so its corrections go here.
--
-- A score write must never fail its whole Game for a value synchronization accepts. Attribution is
-- validated as the IGDB game-page prefix, exactly as game_featured_media checks it; the stricter
-- slug shape added nothing a reader relies on and could have rolled back an otherwise valid Game.
ALTER TABLE catalogue.game_external_score
    DROP CONSTRAINT ck_game_external_score_source_url;
ALTER TABLE catalogue.game_external_score
    ADD CONSTRAINT ck_game_external_score_source_url
        CHECK (source_url ~ '^https://www\.igdb\.com/games/');

-- Appends the per-run player-score counters to the retained-run read contract; existing columns
-- keep their meaning. Reports written before these counters existed read as null, never zero.
CREATE OR REPLACE VIEW catalogue.observability_runs AS
SELECT started_at, completed_at, heartbeat_at, window_from, window_to,
       run_status AS outcome, outcome_code,
       extract(epoch FROM completed_at - started_at)::double precision AS duration_seconds,
       (report #>> '{counters,inspectedReleaseDates}')::bigint AS inspected_release_dates,
       (report #>> '{counters,createdGames}')::bigint AS created_games,
       (report #>> '{counters,updatedGames}')::bigint AS updated_games,
       (report #>> '{counters,unchangedGames}')::bigint AS unchanged_games,
       (report #>> '{counters,deferredGames}')::bigint AS deferred_games,
       (report #>> '{counters,failedGames}')::bigint AS failed_games,
       (report #>> '{counters,createdReleases}')::bigint AS created_releases,
       (report #>> '{counters,updatedReleases}')::bigint AS updated_releases,
       (report #>> '{counters,unchangedReleases}')::bigint AS unchanged_releases,
       (report #>> '{counters,deletedReleases}')::bigint AS deleted_releases,
       (report #>> '{counters,providerRequests}')::bigint AS provider_attempts,
       (report #>> '{counters,providerRetries}')::bigint AS provider_retries,
       (report #>> '{counters,providerLatencyMillis}')::bigint AS provider_latency_millis,
       run_id AS ordering_key,
       (report #>> '{counters,playerScoreObservedGames}')::bigint AS player_score_observed_games,
       (report #>> '{counters,playerScoreAbsentGames}')::bigint AS player_score_absent_games,
       (report #>> '{counters,playerScoreUnavailableGames}')::bigint AS player_score_unavailable_games,
       (report #>> '{counters,playerScoreCreatedGames}')::bigint AS player_score_created_games,
       (report #>> '{counters,playerScoreUpdatedGames}')::bigint AS player_score_updated_games,
       (report #>> '{counters,playerScoreRemovedGames}')::bigint AS player_score_removed_games,
       (report #>> '{counters,playerScoreUnchangedGames}')::bigint AS player_score_unchanged_games
FROM catalogue.synchronization_run;

-- Current player-score check evidence for coverage and check-age panels. One row per checked
-- Game, without any Game, provider or score identity; the panels aggregate it.
CREATE VIEW catalogue.observability_player_scores AS
SELECT score IS NOT NULL AS has_score, score_count, observed_at, changed_at
FROM catalogue.game_external_score
WHERE source_name = 'IGDB' AND score_kind = 'player';
COMMENT ON VIEW catalogue.observability_player_scores IS
    'Checked IGDB player scores without identities; a Game never checked has no row.';

-- Override application default table DML grants: this is a read contract.
REVOKE ALL ON catalogue.observability_player_scores FROM PUBLIC, videogame_app;
