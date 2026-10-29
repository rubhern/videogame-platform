#!/usr/bin/env bash
#
# Prove the personal-data export, erasure, deletion ledger and restore replay (#274).
#
# First the ledger driver against a fake runtime (every interruption point), then the real
# procedure against disposable PostgreSQL, the private-dev Keycloak image and the packaged BFF on
# an internal Docker network; the authenticated executor port is published on loopback only. Synthetic accounts only; no provider, mail
# or private-dev state is involved.
#
# PERSONAL_DATA_REUSE_PACKAGE=true reuses the JAR that scripts/package-application.sh already
# built in the same job.

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repository_root/scripts/backend-artifact.sh"
playwright_image="mcr.microsoft.com/playwright@sha256:47b510dd9230bf0b9bfc9b20102241e577ea7a99334ab4c3ca19f19770e9b8ea"
postgres_image="postgres:18.4-bookworm"
keycloak_image="videogame-platform/keycloak-identity:26.7.5"
# The production runtime base, whose BusyBox wget the private management call uses.
java_image="$(sed -n 's/^ARG JRE_IMAGE=//p' "$repository_root/Dockerfile")"
completion_policy="${PERSONAL_DATA_COMPLETION_POLICY:-independent}"
case "$completion_policy" in
  independent) application_profiles=oidc ;;
  private-dev) application_profiles=oidc,private-dev ;;
  *) printf 'Unknown personal-data completion policy.\n' >&2; exit 2 ;;
esac
run_id="${RANDOM}-$$"
network="videogame-platform-personal-data-${run_id}"
access_network="videogame-platform-personal-data-access-${run_id}"
postgres_container="videogame-platform-personal-data-postgres-${run_id}"
keycloak_container="videogame-platform-personal-data-keycloak-${run_id}"
application_container="videogame-platform-personal-data-application-${run_id}"
work_directory="$(mktemp -d)"
chmod 0700 "$work_directory"
test_failed=false

cleanup() {
  if [[ "$test_failed" == "true" ]]; then
    printf '%s\n' 'Application diagnostics:' >&2
    docker logs "$application_container" 2>&1 | tail -120 >&2 || true
    printf '%s\n' 'Keycloak diagnostics:' >&2
    docker logs "$keycloak_container" 2>&1 | tail -120 >&2 || true
  fi
  docker rm --force "$application_container" "$keycloak_container" "$postgres_container" >/dev/null 2>&1 || true
  docker network rm "$network" "$access_network" >/dev/null 2>&1 || true
  rm -rf -- "$work_directory"
}

trap cleanup EXIT
trap 'test_failed=true' ERR

cd "$repository_root"

python3 -I scripts/test-personal-data-read-model.py
python3 -I scripts/test-personal-data-privacy.py

if ! docker info >/dev/null 2>&1; then
  echo "Docker must be running to prove the personal-data procedure." >&2
  exit 1
fi
[[ -n "$java_image" ]] || { echo "The Dockerfile names no JRE_IMAGE." >&2; exit 1; }

secret() { od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; }
application_password="$(secret)"
migration_password="$(secret)"
keycloak_database_password="$(secret)"
postgres_admin_password="$(secret)"
bff_client_secret="$(secret)"
admin_password_file="$work_directory/keycloak-admin-password"
secret >"$admin_password_file"
chmod 0600 "$admin_password_file"

if [[ "${PERSONAL_DATA_REUSE_PACKAGE:-false}" != "true" ]]; then
  bash scripts/package-application.sh
fi
docker build --quiet --file deploy/private-dev/keycloak/Dockerfile --tag "$keycloak_image" . >/dev/null
application_jar="$(resolve_backend_jar)"

docker network create --internal "$network" >/dev/null

docker run --detach \
  --name "$postgres_container" \
  --network "$network" \
  --network-alias postgres \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD="$postgres_admin_password" \
  --env POSTGRES_DB=postgres \
  --env POSTGRES_INITDB_ARGS="--auth-host=scram-sha-256 --auth-local=trust" \
  --env APPLICATION_DB_PASSWORD="$application_password" \
  --env APPLICATION_MIGRATION_DB_PASSWORD="$migration_password" \
  --env KEYCLOAK_DB_PASSWORD="$keycloak_database_password" \
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

# As in private-dev, the operator tools reach Keycloak only on a loopback port. Docker publishes
# no port from an internal network, so Keycloak alone also joins a regular one for that port.
# A fixed port, because the scenario restarts Keycloak and an ephemeral one could change.
keycloak_port="$(python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
docker network create "$access_network" >/dev/null
docker run --detach \
  --name "$keycloak_container" \
  --network "$access_network" \
  --publish "127.0.0.1:${keycloak_port}:8080" \
  --env KC_DB=postgres \
  --env KC_DB_URL=jdbc:postgresql://postgres:5432/videogame_keycloak \
  --env KC_DB_USERNAME=videogame_keycloak \
  --env KC_DB_PASSWORD="$keycloak_database_password" \
  --env KC_BOOTSTRAP_ADMIN_USERNAME=local-admin \
  --env KC_BOOTSTRAP_ADMIN_PASSWORD="$(cat "$admin_password_file")" \
  --env KC_HEALTH_ENABLED=true \
  --env KC_HOSTNAME=http://keycloak:8080 \
  --env APPLICATION_PUBLIC_ORIGIN=http://application:8080 \
  --env KEYCLOAK_BFF_CLIENT_SECRET="$bff_client_secret" \
  --env LOCAL_TEST_USER_USERNAME=unused-local-user \
  --env LOCAL_TEST_USER_PASSWORD="$(secret)" \
  --volume "$repository_root/docker/keycloak/import:/opt/keycloak/data/import:ro" \
  "$keycloak_image" start-dev --import-realm >/dev/null

docker network connect --alias keycloak "$network" "$keycloak_container"
keycloak_origin="http://127.0.0.1:${keycloak_port}"
keycloak_ready=false
for _ in $(seq 1 180); do
  if python3 -I -c 'import sys, urllib.request; urllib.request.urlopen(sys.argv[1], timeout=3)' \
      "$keycloak_origin/realms/videogame-platform" >/dev/null 2>&1; then
    keycloak_ready=true
    break
  fi
  sleep 1
done
[[ "$keycloak_ready" == true ]] || { echo "Keycloak did not become ready." >&2; exit 1; }

executor_port="$(python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
executor_credential_file="$work_directory/executor.key"
python3 -c 'import secrets; print(secrets.token_hex(32))' > "$executor_credential_file"
chmod 0600 "$executor_credential_file"

docker run --detach \
  --name "$application_container" \
  --network "$access_network" \
  --network-alias application \
  --env SPRING_PROFILES_ACTIVE="$application_profiles" \
  --env APPLICATION_DB_URL=jdbc:postgresql://postgres:5432/videogame_platform \
  --env APPLICATION_DB_USERNAME=videogame_app \
  --env APPLICATION_DB_PASSWORD="$application_password" \
  --env APPLICATION_FLYWAY_ENABLED=true \
  --env APPLICATION_MIGRATION_DB_URL=jdbc:postgresql://postgres:5432/videogame_platform \
  --env APPLICATION_MIGRATION_DB_USERNAME=videogame_app_migrator \
  --env APPLICATION_MIGRATION_DB_PASSWORD="$migration_password" \
  --env SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed \
  --env KEYCLOAK_BFF_CLIENT_SECRET="$bff_client_secret" \
  --env OIDC_ISSUER_URI=http://keycloak:8080/realms/videogame-platform \
  --env OIDC_AUTHORIZATION_URI=http://keycloak:8080/realms/videogame-platform/protocol/openid-connect/auth \
  --env OIDC_TOKEN_URI=http://keycloak:8080/realms/videogame-platform/protocol/openid-connect/token \
  --env OIDC_JWK_SET_URI=http://keycloak:8080/realms/videogame-platform/protocol/openid-connect/certs \
  --env OIDC_USER_INFO_URI=http://keycloak:8080/realms/videogame-platform/protocol/openid-connect/userinfo \
  --env ACCOUNT_DELETION_EXECUTOR_ENABLED=true \
  --env ACCOUNT_DELETION_COMPLETION_POLICY="$completion_policy" \
  --env ACCOUNT_DELETION_EXECUTOR_CREDENTIAL_FILE=/executor.key \
  --env MANAGEMENT_SERVER_ADDRESS=0.0.0.0 \
  --publish "127.0.0.1:${executor_port}:8081" \
  --volume "$executor_credential_file:/executor.key:ro" \
  --env APPLICATION_SESSION_COOKIE_NAME=vgp_session \
  --env APPLICATION_SESSION_COOKIE_SECURE=false \
  --volume "$application_jar:/application.jar:ro" \
  "$java_image" java -jar /application.jar >/dev/null

docker network connect --alias application "$network" "$application_container"

application_ready=false
for _ in $(seq 1 120); do
  if docker exec "$application_container" wget -q -O /dev/null \
      http://127.0.0.1:8081/actuator/health/readiness >/dev/null 2>&1; then
    application_ready=true
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$application_container" 2>/dev/null || true)" != "true" ]]; then
    echo "The packaged application exited before becoming ready." >&2
    exit 1
  fi
  sleep 1
done
[[ "$application_ready" == true ]] || { echo "The packaged application did not become ready." >&2; exit 1; }

PD_COMPLETION_POLICY="$completion_policy" \
PD_EXECUTOR_URL="http://127.0.0.1:${executor_port}/actuator/accountdeletionexecutor" \
PD_EXECUTOR_CREDENTIAL="$executor_credential_file" \
PD_KEYCLOAK_ORIGIN="$keycloak_origin" \
PD_ADMIN_USERNAME=local-admin \
PD_ADMIN_PASSWORD_FILE="$admin_password_file" \
PD_ISSUER=http://keycloak:8080/realms/videogame-platform \
PD_BFF_CLIENT_SECRET="$bff_client_secret" \
PD_POSTGRES="$postgres_container" \
PD_APPLICATION="$application_container" \
PD_KEYCLOAK="$keycloak_container" \
PD_NETWORK="$network" \
PD_PLAYWRIGHT_IMAGE="$playwright_image" \
PD_WORK="$work_directory" \
  python3 -I scripts/test-personal-data-procedure.py

test_failed=false
