#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
playwright_image="mcr.microsoft.com/playwright@sha256:dcc5531e97840b9b5e794f2814476b21571c5124a3fca2267d73041f56e7580e"
java_image="eclipse-temurin@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112"
postgres_image="postgres:18.4-bookworm"
keycloak_image="quay.io/keycloak/keycloak:26.7.0"
identity_run_id="${RANDOM}-$$"
identity_network="videogame-platform-identity-${identity_run_id}"
postgres_container="videogame-platform-identity-postgres-${identity_run_id}"
keycloak_container="videogame-platform-identity-keycloak-${identity_run_id}"
application_container="videogame-platform-identity-application-${identity_run_id}"
identity_tmp_dir="$(mktemp -d)"
test_failed=false

cleanup() {
  if [[ "$test_failed" == "true" ]]; then
    docker logs "$application_container" >"$identity_tmp_dir/application.log" 2>&1 || true
    docker logs "$keycloak_container" >"$identity_tmp_dir/keycloak.log" 2>&1 || true
    printf '%s\n' 'Application diagnostics:' >&2
    tail -200 "$identity_tmp_dir/application.log" >&2 || true
    printf '%s\n' 'Keycloak diagnostics:' >&2
    tail -200 "$identity_tmp_dir/keycloak.log" >&2 || true
  fi
  docker rm --force "$application_container" >/dev/null 2>&1 || true
  docker rm --force "$keycloak_container" >/dev/null 2>&1 || true
  docker rm --force "$postgres_container" >/dev/null 2>&1 || true
  docker network rm "$identity_network" >/dev/null 2>&1 || true
  rm -r -- "$identity_tmp_dir"
}

trap cleanup EXIT
trap 'test_failed=true' ERR

cd "$repository_root"

if ! docker info >/dev/null 2>&1; then
  echo "Docker must be running to execute the real Keycloak identity gate." >&2
  exit 1
fi

application_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
migration_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
keycloak_database_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
postgres_admin_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
keycloak_admin_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
bff_client_secret="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
test_user_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
test_user_username="local-user"

bash scripts/package-application.sh

docker network create --internal "$identity_network" >/dev/null

docker run --detach \
  --name "$postgres_container" \
  --network "$identity_network" \
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

docker exec --env PGPASSWORD="$application_password" "$postgres_container" \
  psql --host=127.0.0.1 --username=videogame_app --dbname=videogame_platform \
  --command="SELECT 1" >/dev/null 2>&1 || {
    docker logs "$postgres_container" >&2
    echo "The disposable identity-test database did not become ready." >&2
    exit 1
  }

docker run --detach \
  --name "$keycloak_container" \
  --network "$identity_network" \
  --network-alias keycloak \
  --env KC_DB=postgres \
  --env KC_DB_URL=jdbc:postgresql://postgres:5432/videogame_keycloak \
  --env KC_DB_USERNAME=videogame_keycloak \
  --env KC_DB_PASSWORD="$keycloak_database_password" \
  --env KC_BOOTSTRAP_ADMIN_USERNAME=local-admin \
  --env KC_BOOTSTRAP_ADMIN_PASSWORD="$keycloak_admin_password" \
  --env KC_HEALTH_ENABLED=true \
  --env KC_HOSTNAME=http://keycloak:8080 \
  --env KEYCLOAK_BFF_CLIENT_SECRET="$bff_client_secret" \
  --env LOCAL_TEST_USER_USERNAME="$test_user_username" \
  --env LOCAL_TEST_USER_PASSWORD="$test_user_password" \
  --volume "$repository_root/docker/keycloak/import:/opt/keycloak/data/import:ro" \
  "$keycloak_image" start-dev --import-realm >/dev/null

for _ in $(seq 1 120); do
  if docker run --rm --network "$identity_network" "$playwright_image" \
      node -e 'fetch("http://keycloak:8080/realms/videogame-platform/.well-known/openid-configuration").then(response => process.exit(response.ok ? 0 : 1)).catch(() => process.exit(1))'; then
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$keycloak_container" 2>/dev/null || true)" != "true" ]]; then
    echo "Keycloak exited before its OIDC discovery endpoint became ready." >&2
    exit 1
  fi
  sleep 1
done

docker run --rm --network "$identity_network" "$playwright_image" \
  node -e 'fetch("http://keycloak:8080/realms/videogame-platform/.well-known/openid-configuration").then(response => process.exit(response.ok ? 0 : 1)).catch(() => process.exit(1))' || {
  echo "Keycloak 26.7 did not become ready for the identity proof." >&2
  exit 1
}

docker run --detach \
  --name "$application_container" \
  --network "$identity_network" \
  --network-alias application \
  --env SPRING_PROFILES_ACTIVE=oidc \
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
  --env APPLICATION_SESSION_COOKIE_NAME=vgp_session \
  --env APPLICATION_SESSION_COOKIE_SECURE=false \
  --volume "$repository_root/backend/target/videogame-platform-backend-0.7.2-SNAPSHOT.jar:/application.jar:ro" \
  "$java_image" java -jar /application.jar >/dev/null

for _ in $(seq 1 90); do
  if docker run --rm --network "$identity_network" "$playwright_image" \
      node -e 'fetch("http://application:8080/actuator/health/readiness").then(response => process.exit(response.ok ? 0 : 1)).catch(() => process.exit(1))'; then
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$application_container" 2>/dev/null || true)" != "true" ]]; then
    echo "The OIDC-enabled packaged application exited before becoming ready." >&2
    exit 1
  fi
  sleep 1
done

docker run --rm --network "$identity_network" "$playwright_image" \
  node -e 'fetch("http://application:8080/actuator/health/readiness").then(response => process.exit(response.ok ? 0 : 1)).catch(() => process.exit(1))' || {
  echo "The OIDC-enabled packaged application did not become ready." >&2
  exit 1
}

docker run --rm \
  --ipc=host \
  --network "$identity_network" \
  --user "$(id -u):$(id -g)" \
  --env PLAYWRIGHT_BASE_URL=http://application:8080 \
  --env OIDC_TEST_USERNAME="$test_user_username" \
  --env OIDC_TEST_PASSWORD="$test_user_password" \
  --volume "$repository_root:/work" \
  --workdir /work \
  "$playwright_image" \
  npm --prefix frontend run test:e2e -- \
    tests/oidc-session.spec.ts \
    --reporter=line \
    --trace=off

test_failed=false
printf '%s\n' 'Real Keycloak 26.7 browser/BFF/session compatibility proof passed.'
