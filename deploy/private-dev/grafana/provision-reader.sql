-- Run as the database administrator after application migrations. Password arrives via stdin.
BEGIN;
SELECT format('CREATE ROLE videogame_grafana LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION CONNECTION LIMIT 2', :'reader_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'videogame_grafana') \gexec
SELECT format('ALTER ROLE videogame_grafana PASSWORD %L', :'reader_password') \gexec
ALTER ROLE videogame_grafana NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION CONNECTION LIMIT 2;
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_auth_members WHERE member = 'videogame_grafana'::regrole) THEN
        RAISE EXCEPTION 'Grafana reader must have no role memberships';
    END IF;
END $$;
REVOKE ALL ON DATABASE videogame_platform FROM videogame_grafana;
REVOKE ALL ON SCHEMA catalogue, ratings, public FROM videogame_grafana;
REVOKE ALL ON ALL TABLES IN SCHEMA catalogue, ratings, public FROM videogame_grafana;
ALTER ROLE videogame_grafana SET default_transaction_read_only = on;
ALTER ROLE videogame_grafana SET statement_timeout = '3s';
ALTER ROLE videogame_grafana SET lock_timeout = '500ms';
ALTER ROLE videogame_grafana SET idle_in_transaction_session_timeout = '5s';
GRANT CONNECT ON DATABASE videogame_platform TO videogame_grafana;
GRANT USAGE ON SCHEMA catalogue, ratings, public TO videogame_grafana;
GRANT SELECT ON catalogue.observability_inventory, catalogue.observability_release_quality,
    catalogue.observability_runs, ratings.observability_summary,
    ratings.observability_score_distribution, ratings.observability_game_rankings,
    public.observability_database TO videogame_grafana;
COMMIT;
