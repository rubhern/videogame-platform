#!/usr/bin/env bash

set -Eeuo pipefail

: "${APPLICATION_DB_PASSWORD:?APPLICATION_DB_PASSWORD is required}"
: "${APPLICATION_MIGRATION_DB_PASSWORD:?APPLICATION_MIGRATION_DB_PASSWORD is required}"
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD is required}"

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=application_password="$APPLICATION_DB_PASSWORD" \
  --set=application_migration_password="$APPLICATION_MIGRATION_DB_PASSWORD" \
  --set=keycloak_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
SELECT format(
  'CREATE ROLE videogame_app LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
  :'application_password'
)
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'videogame_app') \gexec

SELECT format(
  'CREATE ROLE videogame_app_migrator LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
  :'application_migration_password'
)
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'videogame_app_migrator') \gexec

SELECT format(
  'CREATE ROLE videogame_keycloak LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
  :'keycloak_password'
)
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'videogame_keycloak') \gexec

SELECT format(
  'CREATE DATABASE videogame_platform OWNER videogame_app_migrator ENCODING %L TEMPLATE template0',
  'UTF8'
)
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'videogame_platform') \gexec

SELECT format(
  'CREATE DATABASE videogame_keycloak OWNER videogame_keycloak ENCODING %L TEMPLATE template0',
  'UTF8'
)
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'videogame_keycloak') \gexec

REVOKE ALL ON DATABASE videogame_platform FROM PUBLIC;
REVOKE ALL ON DATABASE videogame_keycloak FROM PUBLIC;

GRANT CONNECT ON DATABASE videogame_platform TO videogame_app;
GRANT CONNECT ON DATABASE videogame_platform TO videogame_app_migrator;
GRANT CONNECT ON DATABASE videogame_keycloak TO videogame_keycloak;

\connect videogame_platform

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO videogame_app;

ALTER DEFAULT PRIVILEGES FOR ROLE videogame_app_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO videogame_app;
ALTER DEFAULT PRIVILEGES FOR ROLE videogame_app_migrator IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO videogame_app;
SQL
