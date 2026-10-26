#!/usr/bin/env bash
# Standalone #374 design-gate experiment. Never connects to an existing realm.
set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -d "$repository_root/node_modules/playwright" ]]; then
  echo 'Install the pinned browser tooling first: npm ci --ignore-scripts' >&2
  exit 1
fi
keycloak_image="$(sed -n 's/^ARG KEYCLOAK_IMAGE=//p' "$repository_root/deploy/private-dev/keycloak/Dockerfile")"
if [[ "${PROBE_NATIVE_MAPPER:-false}" == "true" ]]; then
  keycloak_image=videogame-platform/keycloak-identity:26.7.5
  docker build --file "$repository_root/deploy/private-dev/keycloak/Dockerfile" --tag "$keycloak_image" "$repository_root"
fi
playwright_image="$(sed -n 's/^playwright_image="\(.*\)"/\1/p' "$repository_root/scripts/validate-identity.sh")"
probe_id="remember-me-probe-${RANDOM}-$$"
probe_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"

cleanup() {
  docker rm --force "$probe_id" >/dev/null 2>&1 || true
  docker network rm "$probe_id" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create --internal "$probe_id" >/dev/null
# H2 dev-file is sufficient for mapper behavior, not evidence of production persistence.
# No published port, volume, real account, deployed service or production configuration.
docker run --detach --name "$probe_id" --network "$probe_id" --network-alias keycloak \
  --env KC_BOOTSTRAP_ADMIN_USERNAME=probe-admin \
  --env KC_BOOTSTRAP_ADMIN_PASSWORD="$probe_password" \
  --env KC_DB=dev-file --env APPLICATION_PUBLIC_ORIGIN=http://keycloak:8080 \
  "$keycloak_image" start-dev --hostname=http://keycloak:8080 >/dev/null

docker run --rm --network "$probe_id" --ipc=host \
  --env PROBE_ADMIN_PASSWORD="$probe_password" \
  --env PROBE_NATIVE_MAPPER="${PROBE_NATIVE_MAPPER:-false}" \
  --volume "$repository_root:/work:ro" \
  "$playwright_image" node /work/scripts/probe-remember-me-trust.cjs
