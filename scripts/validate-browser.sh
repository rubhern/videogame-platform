#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
playwright_image="mcr.microsoft.com/playwright@sha256:dcc5531e97840b9b5e794f2814476b21571c5124a3fca2267d73041f56e7580e"
java_image="eclipse-temurin@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112"
postgres_image="postgres:18.4-bookworm"
browser_run_id="${RANDOM}-$$"
browser_network="videogame-platform-browser-${browser_run_id}"
postgres_container="videogame-platform-browser-postgres-${browser_run_id}"
application_container="videogame-platform-browser-application-${browser_run_id}"
browser_tmp_dir="$(mktemp -d)"
application_log="$browser_tmp_dir/application.log"

cleanup() {
  docker rm --force "$application_container" >/dev/null 2>&1 || true
  docker rm --force "$postgres_container" >/dev/null 2>&1 || true
  docker network rm "$browser_network" >/dev/null 2>&1 || true
  rm -r -- "$browser_tmp_dir"
}

trap cleanup EXIT

cd "$repository_root"

if ! docker info >/dev/null 2>&1; then
  echo "Docker must be running to execute the pinned Playwright browser gate." >&2
  exit 1
fi

application_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
migration_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
keycloak_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
postgres_admin_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"

bash scripts/package-application.sh

docker network create --internal "$browser_network" >/dev/null

docker run --detach \
  --name "$postgres_container" \
  --network "$browser_network" \
  --network-alias postgres \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD="$postgres_admin_password" \
  --env POSTGRES_DB=postgres \
  --env POSTGRES_INITDB_ARGS="--auth-host=scram-sha-256 --auth-local=trust" \
  --env APPLICATION_DB_PASSWORD="$application_password" \
  --env APPLICATION_MIGRATION_DB_PASSWORD="$migration_password" \
  --env KEYCLOAK_DB_PASSWORD="$keycloak_password" \
  --volume "$repository_root/docker/postgres/init:/docker-entrypoint-initdb.d:ro" \
  "$postgres_image" >/dev/null

for _ in $(seq 1 90); do
  if docker exec --env PGPASSWORD="$application_password" "$postgres_container" \
      psql --host=127.0.0.1 --username=videogame_app --dbname=videogame_platform \
      --command="SELECT 1" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

docker exec --env PGPASSWORD="$application_password" "$postgres_container" \
  psql --host=127.0.0.1 --username=videogame_app --dbname=videogame_platform \
  --command="SELECT 1" >/dev/null 2>&1 || {
    docker logs "$postgres_container" >&2
    echo "The disposable PostgreSQL browser-test database did not become ready." >&2
    exit 1
  }

docker run --detach \
  --name "$application_container" \
  --network "$browser_network" \
  --network-alias application \
  --env APPLICATION_DB_URL=jdbc:postgresql://postgres:5432/videogame_platform \
  --env APPLICATION_DB_USERNAME=videogame_app \
  --env APPLICATION_DB_PASSWORD="$application_password" \
  --env APPLICATION_FLYWAY_ENABLED=true \
  --env APPLICATION_MIGRATION_DB_URL=jdbc:postgresql://postgres:5432/videogame_platform \
  --env APPLICATION_MIGRATION_DB_USERNAME=videogame_app_migrator \
  --env APPLICATION_MIGRATION_DB_PASSWORD="$migration_password" \
  --env SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed \
  --volume "$repository_root/backend/target/videogame-platform-backend-0.7.2-SNAPSHOT.jar:/application.jar:ro" \
  "$java_image" \
  java -jar /application.jar >/dev/null

for _ in $(seq 1 90); do
  if docker run --rm \
      --network "$browser_network" \
      "$playwright_image" \
      node -e 'fetch("http://application:8080/actuator/health/readiness").then(response => process.exit(response.ok ? 0 : 1)).catch(() => process.exit(1))'; then
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$application_container" 2>/dev/null || true)" != "true" ]]; then
    docker logs "$application_container" >"$application_log" 2>&1 || true
    cat "$application_log" >&2
    echo "The packaged application exited before becoming ready." >&2
    exit 1
  fi
  sleep 1
done

if ! docker run --rm \
    --network "$browser_network" \
    "$playwright_image" \
    node -e 'fetch("http://application:8080/actuator/health/readiness").then(response => process.exit(response.ok ? 0 : 1)).catch(() => process.exit(1))'; then
  docker logs "$application_container" >"$application_log" 2>&1 || true
  cat "$application_log" >&2
  echo "The packaged application did not become ready." >&2
  exit 1
fi

docker run --rm \
  --ipc=host \
  --network "$browser_network" \
  --user "$(id -u):$(id -g)" \
  --env HOME=/tmp \
  --env PLAYWRIGHT_BASE_URL=http://application:8080 \
  --volume "$repository_root:/work" \
  --workdir /work \
  "$playwright_image" \
  npm run frontend:test:e2e
