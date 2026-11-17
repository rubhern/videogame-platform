#!/usr/bin/env bash

# Proves the reviewed Google and Microsoft sign-in shape (#548, ADR-0045) against a disposable
# Keycloak built from the repository image and the production realm import, plus a Mailpit sink.
# Two OIDC providers backed by a second disposable realm stand in for Google and Microsoft; no
# real provider, credential, local stack or private-dev data is used. Keycloak and the mail sink
# publish only on random loopback ports and are removed on exit.

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
keycloak_image="videogame-platform/keycloak-social-sign-in:26.7.5"
mailpit_image="$(sed -n 's/^ *image: //p' "$repository_root/deploy/private-dev/compose.mail.yaml")"
run_id="${RANDOM}-$$"
network="videogame-platform-social-sign-in-${run_id}"
keycloak_container="videogame-platform-social-sign-in-keycloak-${run_id}"
mailpit_container="videogame-platform-social-sign-in-mailpit-${run_id}"
test_failed=false

cleanup() {
  if [[ "$test_failed" == "true" ]]; then
    printf '%s\n' 'Keycloak diagnostics:' >&2
    docker logs "$keycloak_container" 2>&1 | tail -120 >&2 || true
  fi
  docker rm --force "$keycloak_container" "$mailpit_container" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}

trap cleanup EXIT
trap 'test_failed=true' ERR

cd "$repository_root"

if ! docker info >/dev/null 2>&1; then
  echo "Docker must be running to prove social sign-in." >&2
  exit 1
fi

python3 -I scripts/test-production-social-sign-in.py

free_port() {
  python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

random_secret() {
  od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
}

keycloak_port="$(free_port)"
mailpit_port="$(free_port)"
admin_password="$(random_secret)"

docker build --quiet --file deploy/private-dev/keycloak/Dockerfile --tag "$keycloak_image" . >/dev/null
docker network create "$network" >/dev/null

docker run --detach --name "$mailpit_container" --network "$network" --network-alias mail-sink \
  --publish "127.0.0.1:${mailpit_port}:8025" --user 65534:65534 "$mailpit_image" >/dev/null

# Development mode with an embedded store: the realm import and the image are the production
# ones; the database, TLS and edge are proven by scripts/validate-production-identity.sh.
docker run --detach --name "$keycloak_container" --network "$network" \
  --publish "127.0.0.1:${keycloak_port}:8080" \
  --env KC_DB=dev-file \
  --env KC_BOOTSTRAP_ADMIN_USERNAME=social-sign-in-admin \
  --env KC_BOOTSTRAP_ADMIN_PASSWORD="$admin_password" \
  --env KEYCLOAK_BFF_CLIENT_SECRET="$(random_secret)" \
  --env APPLICATION_PUBLIC_HOST=gameometro.test \
  --env APPLICATION_PUBLIC_ORIGIN=https://gameometro.test \
  --env KEYCLOAK_SMTP_HOST=mail-sink \
  --env KEYCLOAK_SMTP_PORT=1025 \
  --env KEYCLOAK_SMTP_FROM=no-reply@gameometro.test \
  --env KEYCLOAK_SMTP_USER=unused \
  --env KEYCLOAK_SMTP_PASSWORD=unused \
  --volume "$repository_root/deploy/public-production/keycloak/import:/opt/keycloak/data/import:ro" \
  "$keycloak_image" start-dev --import-realm --hostname="http://localhost:${keycloak_port}" >/dev/null

for _ in $(seq 1 90); do
  if docker logs "$keycloak_container" 2>&1 | grep -q "Listening on"; then
    break
  fi
  sleep 2
done
docker logs "$keycloak_container" 2>&1 | grep -q "Listening on" || { echo "Keycloak did not start." >&2; false; }

KEYCLOAK_TEST_ORIGIN="http://localhost:${keycloak_port}" \
KEYCLOAK_TEST_ADMIN_USERNAME=social-sign-in-admin \
KEYCLOAK_TEST_ADMIN_PASSWORD="$admin_password" \
MAILPIT_API_ORIGIN="http://127.0.0.1:${mailpit_port}" \
MAIL_TEST_HOST=mail-sink \
  python3 -I scripts/test-social-sign-in-keycloak.py
