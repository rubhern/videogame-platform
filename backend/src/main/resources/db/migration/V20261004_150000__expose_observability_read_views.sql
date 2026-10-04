-- Module-owned read contracts for the private Grafana reader. No new stored state.
-- Definer views expose only deliberate aggregates/public context, never rating owners.
CREATE VIEW catalogue.observability_inventory AS
SELECT g.games, r.releases, g.provider_covers, g.fallback_covers, e.featured_evidence_games
FROM (SELECT count(*) AS games,
             count(*) FILTER (WHERE cover_usage_mode = 'provider_cdn_reference') AS provider_covers,
             count(*) FILTER (WHERE cover_usage_mode = 'product_owned') AS fallback_covers
      FROM catalogue.game_snapshot) g
CROSS JOIN (SELECT count(*) AS releases FROM catalogue.release_snapshot) r
CROSS JOIN (SELECT count(*) AS featured_evidence_games FROM catalogue.game_featured_evidence) e;

CREATE VIEW catalogue.observability_release_quality AS
SELECT release_status AS provider_status, date_precision, verification_level, review_status,
       last_synchronized_at, last_verified_at
FROM catalogue.release_snapshot;
COMMENT ON VIEW catalogue.observability_release_quality IS
    'Current release evidence, not clock-derived effective lifecycle status; no record identities.';

CREATE VIEW catalogue.observability_runs AS
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
       run_id AS ordering_key
FROM catalogue.synchronization_run;
COMMENT ON VIEW catalogue.observability_runs IS
    'Retained manual runs; skipped commands have no durable run. ordering_key provides total ordering only.';

CREATE VIEW ratings.observability_summary AS
SELECT count(*) AS active_ratings, count(DISTINCT user_id) AS users_with_ratings,
       count(DISTINCT game_id) AS rated_games,
       count(*)::double precision / NULLIF(count(DISTINCT user_id), 0) AS ratings_per_rating_user
FROM ratings.rating;
CREATE VIEW ratings.observability_score_distribution AS
SELECT value AS score, count(*) AS ratings FROM ratings.rating GROUP BY value;
CREATE VIEW ratings.observability_game_rankings AS
SELECT r.game_id AS ordering_key, g.canonical_title AS game, g.slug,
       count(*) AS votes, avg(r.value)::double precision AS mean_score
FROM ratings.rating r LEFT JOIN ratings.game_listing g ON g.game_id = r.game_id
GROUP BY r.game_id, g.game_id, g.canonical_title, g.slug;
COMMENT ON VIEW ratings.observability_game_rankings IS
    'Active rating aggregates with Ratings-owned public game projection; no cross-module join or user identity.';

CREATE VIEW public.observability_database AS
SELECT numbackends AS connections, deadlocks, temp_bytes,
       stats_reset,
       (SELECT setting::integer FROM pg_settings WHERE name = 'max_connections') AS server_connection_limit
FROM pg_stat_database WHERE datname = current_database();
COMMENT ON VIEW public.observability_database IS
    'Current application-database connections vs whole-server limit; cumulative statistics reset independently.';
-- Override application default table DML grants: these are read contracts.
REVOKE ALL ON catalogue.observability_inventory, catalogue.observability_release_quality,
    catalogue.observability_runs, ratings.observability_summary,
    ratings.observability_score_distribution, ratings.observability_game_rankings,
    public.observability_database FROM PUBLIC, videogame_app;
