#!/usr/bin/env bash
set -Eeuo pipefail
export LC_ALL=C

tool_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$tool_dir/../.." && pwd)"
container=videogame-platform-performance-postgres
image=postgres:18.4-bookworm
database=videogame_performance
boundary_label=videogame-platform.performance-data

fail() { printf '%s\n' "$*" >&2; exit 1; }
usage() {
  cat <<'TEXT'
Usage: bash tools/performance/dataset.sh current
       bash tools/performance/dataset.sh generate growth|large [YYYY-MM-DD]
       bash tools/performance/dataset.sh inspect
       bash tools/performance/dataset.sh stop

current prints a read-only JSON profile of the supported local catalogue.
generate replaces ONLY the dedicated disposable performance dataset.
The default reference date is 2026-10-07; pass an explicit date for later runs.
inspect prints the disposable database profile. stop removes it and its anonymous volume.
TEXT
}

command="${1:-help}"
case "$command" in
  help|--help|-h) usage; exit 0 ;;
  current|inspect|stop) [[ $# == 1 ]] || fail 'Unexpected arguments.' ;;
  generate)
    [[ $# == 2 || $# == 3 ]] || fail 'Expected generate growth|large [YYYY-MM-DD].'
    profile="$2"
    [[ "$profile" == growth || "$profile" == large ]] || fail 'Unknown synthetic profile.'
    as_of="${3:-2026-10-07}"
    [[ "$as_of" =~ ^20[0-9]{2}-[0-9]{2}-[0-9]{2}$ ]] || fail 'Reference date must be YYYY-MM-DD in 2000..2099.'
    [[ "$(date -d "$as_of" +%F 2>/dev/null)" == "$as_of" ]] || fail 'Invalid reference date.'
    ;;
  *) usage >&2; exit 1 ;;
esac

# Never accept a database URL, container name, volume name, image, or remote engine override.
if [[ -n "${DOCKER_CONTEXT:-}" ]]; then
  endpoint="$(docker context inspect "$DOCKER_CONTEXT" --format '{{.Endpoints.docker.Host}}')"
else
  endpoint="${DOCKER_HOST:-$(docker context inspect --format '{{.Endpoints.docker.Host}}')}"
fi
case "$endpoint" in
  unix:///*|npipe:////./pipe/*) ;;
  *) fail 'Performance tooling requires a local Docker engine.' ;;
esac
docker info >/dev/null 2>&1 || fail 'Docker must be running (enable WSL integration on Windows).'

if [[ "$command" == current ]]; then
  local_container="$(docker ps --filter label=com.docker.compose.project=videogame-platform \
    --filter label=com.docker.compose.service=postgres --format '{{.ID}}')"
  [[ -n "$local_container" && "$local_container" != *$'\n'* ]] || \
    fail 'Start the supported local PostgreSQL service; expected exactly one running local catalogue container.'
  # This role is used only inside a SQL-enforced read-only, repeatable-read transaction.
  docker exec -i "$local_container" psql -X -qAt -v ON_ERROR_STOP=1 \
    -U videogame_app -d videogame_platform -v profile=current < "$tool_dir/profile.sql"
  exit 0
fi

check_container() {
  local identity mounts
  identity="$(docker inspect --format "{{index .Config.Labels \"$boundary_label\"}}|{{.Config.Image}}|{{.HostConfig.AutoRemove}}" "$container")"
  [[ "$identity" == "disposable|$image|true" ]] || fail 'Refusing a container outside the disposable performance boundary.'
  mounts="$(docker inspect --format '{{range .Mounts}}{{.Type}}:{{.Name}}:{{.Destination}}{{println}}{{end}}' "$container")"
  [[ "$mounts" =~ ^volume:[a-f0-9]{64}:/var/lib/postgresql$ ]] || \
    fail 'Refusing a container with a bind mount, named/persistent volume, or unexpected mounts.'
}

performance_sql() {
  docker exec -i "$container" psql -X -qAt -v ON_ERROR_STOP=1 -U postgres -d "$database" "$@"
}

if [[ "$command" == stop ]]; then
  if docker container inspect "$container" >/dev/null 2>&1; then
    check_container
    # Some Docker engines retain volumes supplied through --mount even with --rm.
    # Capture only the mount already verified above; rm without --force refuses
    # a volume that another container has started using.
    performance_volume="$(docker inspect --format '{{range .Mounts}}{{.Name}}{{end}}' "$container")"
    docker rm --force "$container" >/dev/null
    if docker volume inspect "$performance_volume" >/dev/null 2>&1; then
      docker volume rm "$performance_volume" >/dev/null
    fi
    printf 'Removed the disposable performance container and anonymous volume.\n' >&2
  fi
  exit 0
fi

schema_checksum="$(cat "$repository_root"/backend/src/main/resources/db/migration/V*.sql | sha256sum | cut -d ' ' -f 1)"
if ! docker container inspect "$container" >/dev/null 2>&1; then
  [[ "$command" == generate ]] || fail 'No performance database exists; generate a profile first.'
  docker run --detach --rm --name "$container" --label "$boundary_label=disposable" \
    --publish 127.0.0.1:55432:5432 --memory=1g --cpus=2 \
    --mount type=volume,destination=/var/lib/postgresql \
    --env POSTGRES_DB="$database" --env POSTGRES_PASSWORD=performance-disposable-only \
    "$image" >/dev/null
  check_container
  ready=false
  for ((attempt=0; attempt<60; attempt++)); do
    if docker exec "$container" sh -c \
      'test "$(cat /proc/1/comm)" = postgres && pg_isready -U postgres -d videogame_performance' >/dev/null 2>&1; then
      ready=true; break
    fi
    sleep 1
  done
  [[ "$ready" == true ]] || fail 'Performance PostgreSQL did not become ready; inspect its logs, then stop it.'
  # Replay the immutable schema SQL, never dev-seed. This fixture has no Flyway history;
  # the backend must connect with Flyway disabled. A schema change requires stop/regenerate.
  {
    cat <<'SQL'
BEGIN;
CREATE ROLE videogame_app LOGIN PASSWORD 'performance-local-only';
CREATE ROLE videogame_app_migrator NOLOGIN;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO videogame_app;
SQL
    cat "$repository_root"/backend/src/main/resources/db/migration/V*.sql
    cat <<'SQL'
CREATE SCHEMA performance_data;
REVOKE ALL ON SCHEMA performance_data FROM PUBLIC;
CREATE TABLE performance_data.boundary (
    purpose text PRIMARY KEY CHECK (purpose = 'disposable-performance-only'),
    schema_checksum text NOT NULL,
    profile text,
    reference_date date
);
INSERT INTO performance_data.boundary (purpose, schema_checksum)
VALUES ('disposable-performance-only', :'schema_checksum');
COMMIT;
SQL
  } | performance_sql -v schema_checksum="$schema_checksum" >/dev/null
fi
check_container

if [[ "$command" == inspect ]]; then
  performance_sql -v profile=synthetic < "$tool_dir/profile.sql"
  exit 0
fi

started=$SECONDS
performance_sql -v profile="$profile" -v as_of="$as_of" -v schema_checksum="$schema_checksum" \
  < "$tool_dir/generate.sql"
printf 'Generated and checked %s in %ss (including ANALYZE; excluding initial schema setup).\n' \
  "$profile" "$((SECONDS-started))" >&2
performance_sql -v profile="$profile" < "$tool_dir/profile.sql"
