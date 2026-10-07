-- Offline inventory only: no row identities, provider payloads, credentials or writes.
\set ON_ERROR_STOP on
\if :{?profile}
\else
\set profile current
\endif
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '120s';
SET LOCAL lock_timeout = '3s';
SET LOCAL timezone = 'UTC';

WITH releases_per_game AS (
    SELECT g.game_id, count(r.release_id) AS n
    FROM catalogue.game_snapshot g
    LEFT JOIN catalogue.release_snapshot r USING (game_id)
    GROUP BY g.game_id
), release_buckets AS (
    SELECT CASE WHEN n = 0 THEN '0' WHEN n = 1 THEN '1'
                WHEN n <= 5 THEN '2..5' WHEN n <= 10 THEN '6..10'
                WHEN n <= 25 THEN '11..25' ELSE '>25' END AS bucket,
           count(*) AS games
    FROM releases_per_game GROUP BY 1
), ratings_per_user AS (
    SELECT count(*) AS n FROM ratings.rating GROUP BY user_id
), user_buckets AS (
    SELECT CASE WHEN n <= 10 THEN '1..10' WHEN n <= 100 THEN '11..100'
                WHEN n <= 1000 THEN '101..1000' ELSE '>1000' END AS bucket,
           count(*) AS users
    FROM ratings_per_user GROUP BY 1
), precision_counts AS (
    SELECT date_precision, count(*) AS releases FROM catalogue.release_snapshot GROUP BY 1
), status_counts AS (
    SELECT release_status, count(*) AS releases FROM catalogue.release_snapshot GROUP BY 1
), source_counts AS (
    SELECT source_kind, count(*) AS releases FROM catalogue.release_snapshot GROUP BY 1
), platform_counts AS (
    SELECT p.code, count(*) AS releases FROM catalogue.release_snapshot r
    JOIN catalogue.platform p USING (platform_id) GROUP BY p.code
    ORDER BY releases DESC, p.code LIMIT 20
), region_counts AS (
    SELECT p.code, count(*) AS releases FROM catalogue.release_snapshot r
    JOIN catalogue.region p USING (region_id) GROUP BY p.code
    ORDER BY releases DESC, p.code LIMIT 20
)
SELECT jsonb_pretty(jsonb_build_object(
    'profile', :'profile',
    'measured_at_utc', CURRENT_TIMESTAMP,
    'evaluation_date_madrid', (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Madrid')::date,
    'database', current_database(),
    'postgresql_version', current_setting('server_version'),
    'counts', jsonb_build_object(
        'games', (SELECT count(*) FROM catalogue.game),
        'releases', (SELECT count(*) FROM catalogue.game_release),
        'ratings', (SELECT count(*) FROM ratings.rating),
        'visible_games', (SELECT count(*) FROM catalogue.game_snapshot),
        'visible_releases', (SELECT count(*) FROM catalogue.release_snapshot),
        'current_revisions', (SELECT count(*) FROM catalogue.catalogue_publication WHERE is_current),
        'users_with_ratings', (SELECT count(*) FROM ratings_per_user),
        'platforms', (SELECT count(*) FROM catalogue.platform),
        'regions', (SELECT count(*) FROM catalogue.region),
        'rated_games', (SELECT count(DISTINCT game_id) FROM ratings.rating),
        'approved_aliases', (SELECT count(*) FROM catalogue.game_alias WHERE approval_status='approved'),
        'games_with_genres', (SELECT count(DISTINCT game_id) FROM catalogue.game_genre)),
    'releases_per_game', COALESCE((SELECT jsonb_object_agg(bucket, games) FROM release_buckets), '{}'::jsonb),
    'ratings_per_user', COALESCE((SELECT jsonb_object_agg(bucket, users) FROM user_buckets), '{}'::jsonb),
    'release_date_precision', COALESCE((SELECT jsonb_object_agg(date_precision, releases) FROM precision_counts), '{}'::jsonb),
    'persisted_release_evidence', COALESCE((SELECT jsonb_object_agg(release_status, releases) FROM status_counts), '{}'::jsonb),
    'release_source_kind', COALESCE((SELECT jsonb_object_agg(source_kind, releases) FROM source_counts), '{}'::jsonb),
    'release_dates', (SELECT jsonb_build_object('earliest_period_start',min(period_start),
        'latest_period_end',max(period_end),'last_synchronized_at',max(last_synchronized_at))
        FROM catalogue.release_snapshot),
    'top_20_platforms', COALESCE((SELECT jsonb_object_agg(code, releases) FROM platform_counts), '{}'::jsonb),
    'top_20_regions', COALESCE((SELECT jsonb_object_agg(code, releases) FROM region_counts), '{}'::jsonb),
    'storage', jsonb_build_object(
        'database_bytes', pg_database_size(current_database()),
        'catalogue_and_ratings_bytes', (SELECT COALESCE(sum(pg_total_relation_size(c.oid)), 0)
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname IN ('catalogue','ratings') AND c.relkind='r'))
));
COMMIT;
