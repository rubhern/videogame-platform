#!/usr/bin/env bash

# Proves the private-dev identity alignment and the explicit mail transports against a
# disposable copy of the shared realm on an internal Docker network: PostgreSQL, the
# optimized private-dev Keycloak image over verified HTTPS, the plain Mailpit sink of the
# realm import, and an authenticated STARTTLS Mailpit standing in for the real relay. No
# provider is contacted and no local or private-dev configuration, secret or data is used.

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
playwright_image="mcr.microsoft.com/playwright@sha256:47b510dd9230bf0b9bfc9b20102241e577ea7a99334ab4c3ca19f19770e9b8ea"
postgres_image="postgres:18.4-bookworm"
keycloak_image="videogame-platform/keycloak-private-dev-identity:26.7.5"
mailpit_image="$(sed -n 's/^ *image: //p' "$repository_root/deploy/private-dev/compose.mail.yaml")"
run_id="${RANDOM}-$$"
network="videogame-platform-private-dev-identity-${run_id}"
postgres_container="videogame-platform-private-dev-identity-postgres-${run_id}"
keycloak_container="videogame-platform-private-dev-identity-keycloak-${run_id}"
sink_container="videogame-platform-private-dev-identity-sink-${run_id}"
relay_container="videogame-platform-private-dev-identity-relay-${run_id}"
identity_host="vgpdev.gameometro.test"
relay_host="relay.gameometro.test"
identity_origin="https://$identity_host:8443"
run_dir="$(mktemp -d)"
test_failed=false

cleanup() {
  if [[ "$test_failed" == "true" ]]; then
    printf '%s\n' 'Keycloak diagnostics:' >&2
    docker logs "$keycloak_container" 2>&1 | tail -150 >&2 || true
    printf '%s\n' 'Relay diagnostics:' >&2
    docker logs "$relay_container" 2>&1 | tail -40 >&2 || true
  fi
  docker rm --force "$keycloak_container" "$sink_container" "$relay_container" "$postgres_container" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf -- "$run_dir"
}

trap cleanup EXIT
trap 'test_failed=true' ERR

cd "$repository_root"

if ! docker info >/dev/null 2>&1; then
  echo "Docker must be running to prove the private-dev identity alignment." >&2
  exit 1
fi

random_secret() {
  od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
}

postgres_admin_password="$(random_secret)"
keycloak_database_password="$(random_secret)"
keycloak_admin_password="$(random_secret)"
bff_client_secret="$(random_secret)"
relay_username="private-dev-relay-${run_id}"
relay_password="$(random_secret)"

# A throwaway CA signs the identity and relay certificates, so the HTTPS origin and the
# relay's STARTTLS submission are both verified rather than trusted blindly.
pki="$run_dir/pki"
mkdir -p "$pki" "$run_dir/evidence" "$run_dir/mail" "$run_dir/relay/secrets"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=Private-dev identity test CA" \
  -keyout "$pki/ca.key" -out "$pki/ca.crt" >/dev/null 2>&1
for host in "$identity_host" "$relay_host"; do
  openssl req -newkey rsa:2048 -nodes -subj "/CN=$host" \
    -keyout "$pki/$host.key" -out "$pki/$host.csr" >/dev/null 2>&1
  printf 'subjectAltName=DNS:%s\nextendedKeyUsage=serverAuth\n' "$host" >"$pki/$host.ext"
  openssl x509 -req -in "$pki/$host.csr" -CA "$pki/ca.crt" -CAkey "$pki/ca.key" -CAcreateserial \
    -days 1 -extfile "$pki/$host.ext" -out "$pki/$host.crt" >/dev/null 2>&1
done
rm -f -- "$pki/ca.key" "$pki"/*.csr "$pki"/*.ext "$pki"/*.srl
printf '%s:%s\n' "$relay_username" "$relay_password" >"$run_dir/mail/smtp-auth"
# The same protected-input shapes as vgpdev: runtime.env keys and independent secret files.
printf '%s\n' "$relay_username" >"$run_dir/relay/secrets/keycloak-smtp-username"
printf '%s\n' "$relay_password" >"$run_dir/relay/secrets/keycloak-smtp-password"
cat >"$run_dir/relay/runtime.env" <<RELAY
PRIVATE_DEV_SECRETS_DIR=/run/relay/secrets
PRIVATE_DEV_SMTP_RELAY_HOST=$relay_host
PRIVATE_DEV_SMTP_RELAY_PORT=587
PRIVATE_DEV_SMTP_RELAY_FROM=no-reply@mail.gameometro.test
PRIVATE_DEV_SMTP_RELAY_REPLY_TO=hola@gameometro.test
RELAY
# Disposable inputs for containers running as unrelated users; the directory is removed on exit.
chmod 0755 "$pki" "$run_dir/mail" "$run_dir/relay" "$run_dir/relay/secrets"
chmod 0644 "$pki"/* "$run_dir/mail/smtp-auth" "$run_dir/relay/runtime.env" "$run_dir/relay/secrets"/*
chmod 0777 "$run_dir/evidence"

docker build --quiet --file deploy/private-dev/keycloak/Dockerfile --tag "$keycloak_image" . >/dev/null

docker network create --internal "$network" >/dev/null

mailpit_options=(--user 65534:65534 --read-only --tmpfs /tmp:rw,noexec,nosuid,size=32m --cap-drop ALL)

# The realm import's unauthenticated sink, reached as mailpit:1025 exactly as in private-dev.
docker run --detach --name "$sink_container" --network "$network" --network-alias mailpit \
  "${mailpit_options[@]}" "$mailpit_image" \
  --max=100 --smtp-disable-rdns --disable-version-check --allowed-hosts=mailpit:8025 >/dev/null

# The relay stand-in accepts only authenticated STARTTLS submission, like the real provider.
docker run --detach --name "$relay_container" --network "$network" --network-alias "$relay_host" \
  "${mailpit_options[@]}" \
  --volume "$pki:/run/pki:ro" \
  --volume "$run_dir/mail:/run/mail:ro" \
  "$mailpit_image" \
  --max=100 --smtp-disable-rdns --disable-version-check \
  --allowed-hosts="$relay_host:8025" \
  --smtp=0.0.0.0:587 \
  --smtp-tls-cert="/run/pki/$relay_host.crt" --smtp-tls-key="/run/pki/$relay_host.key" \
  --smtp-require-starttls \
  --smtp-auth-file=/run/mail/smtp-auth >/dev/null

docker run --detach \
  --name "$postgres_container" \
  --network "$network" \
  --network-alias postgres \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD="$postgres_admin_password" \
  --env POSTGRES_DB=postgres \
  --env POSTGRES_INITDB_ARGS="--auth-host=scram-sha-256 --auth-local=trust" \
  --env APPLICATION_DB_PASSWORD="$(random_secret)" \
  --env APPLICATION_MIGRATION_DB_PASSWORD="$(random_secret)" \
  --env KEYCLOAK_DB_PASSWORD="$keycloak_database_password" \
  --volume "$repository_root/docker/postgres/init:/docker-entrypoint-initdb.d:ro" \
  "$postgres_image" >/dev/null

for _ in $(seq 1 90); do
  if docker exec --env PGPASSWORD="$keycloak_database_password" "$postgres_container" \
      psql --host=127.0.0.1 --username=videogame_keycloak --dbname=videogame_keycloak \
      --command="SELECT 1" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec --env PGPASSWORD="$keycloak_database_password" "$postgres_container" \
  psql --host=127.0.0.1 --username=videogame_keycloak --dbname=videogame_keycloak \
  --command="SELECT 1" >/dev/null 2>&1 || {
    echo "The disposable identity database did not become ready." >&2
    exit 1
  }

# Like private-dev Compose, only the shared realm file is mounted: no synthetic user import.
docker run --detach \
  --name "$keycloak_container" \
  --network "$network" \
  --network-alias "$identity_host" \
  --env KC_DB_URL=jdbc:postgresql://postgres:5432/videogame_keycloak \
  --env KC_DB_USERNAME=videogame_keycloak \
  --env KC_DB_PASSWORD="$keycloak_database_password" \
  --env KC_BOOTSTRAP_ADMIN_USERNAME=private-dev-identity-admin \
  --env KC_BOOTSTRAP_ADMIN_PASSWORD="$keycloak_admin_password" \
  --env KC_HOSTNAME="$identity_origin" \
  --env KC_HTTPS_PORT=8443 \
  --env KC_HTTP_ENABLED=false \
  --env KC_HTTPS_CERTIFICATE_FILE="/run/pki/$identity_host.crt" \
  --env KC_HTTPS_CERTIFICATE_KEY_FILE="/run/pki/$identity_host.key" \
  --env KC_TRUSTSTORE_PATHS=/run/pki/ca.crt \
  --env APPLICATION_PUBLIC_ORIGIN="https://$identity_host" \
  --env KEYCLOAK_BFF_CLIENT_SECRET="$bff_client_secret" \
  --volume "$pki:/run/pki:ro" \
  --volume "$repository_root/docker/keycloak/import/videogame-platform-realm.json:/opt/keycloak/data/import/videogame-platform-realm.json:ro" \
  "$keycloak_image" start --optimized --import-realm >/dev/null

run_python() {
  docker run --rm \
    --network "$network" \
    --user "$(id -u):$(id -g)" \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,size=16m \
    --env SSL_CERT_FILE=/run/pki/ca.crt \
    --env KEYCLOAK_TEST_ORIGIN="$identity_origin" \
    --env KEYCLOAK_TEST_ADMIN_USERNAME=private-dev-identity-admin \
    --env KEYCLOAK_TEST_ADMIN_PASSWORD="$keycloak_admin_password" \
    --env KEYCLOAK_TEST_CLIENT_SECRET="$bff_client_secret" \
    --env APPLICATION_TEST_ORIGIN="https://$identity_host" \
    --env MAILPIT_API_ORIGIN=http://mailpit:8025 \
    --env RELAY_API_ORIGIN="http://$relay_host:8025" \
    --env RELAY_ENV_FILE=/run/relay/runtime.env \
    --env TEST_EVIDENCE_DIR=/run/evidence \
    --volume "$pki:/run/pki:ro" \
    --volume "$run_dir/relay:/run/relay:ro" \
    --volume "$run_dir/evidence:/run/evidence:rw" \
    --volume "$repository_root:/work:ro" \
    --workdir /work \
    "$playwright_image" \
    python3 "$@"
}

ready=false
for _ in $(seq 1 120); do
  if run_python -c 'import os, urllib.request; urllib.request.urlopen(os.environ["KEYCLOAK_TEST_ORIGIN"] + "/realms/videogame-platform/.well-known/openid-configuration", timeout=5)' >/dev/null 2>&1; then
    ready=true
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$keycloak_container" 2>/dev/null || true)" != "true" ]]; then
    echo "Keycloak exited before the shared realm became ready." >&2
    test_failed=true
    exit 1
  fi
  sleep 1
done
if [[ "$ready" != "true" ]]; then
  echo "Keycloak 26.7 did not serve the shared realm over verified HTTPS." >&2
  test_failed=true
  exit 1
fi

run_python scripts/test-private-dev-identity-alignment.py

# Credentials, addresses and links handled by the journey must never reach Keycloak's log.
docker logs "$keycloak_container" >"$run_dir/keycloak.log" 2>&1
printf '%s\n' "$bff_client_secret" "$relay_password" "$keycloak_admin_password" "$keycloak_database_password" \
  >>"$run_dir/evidence/sensitive-values.txt"
python3 - "$run_dir/keycloak.log" "$run_dir/evidence/sensitive-values.txt" <<'PY'
import pathlib, sys

log = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
values = [value for value in pathlib.Path(sys.argv[2]).read_text(encoding="utf-8").splitlines() if value]
assert len(values) >= 15, "the journey recorded too few sensitive values to make the log check meaningful"
leaked = [index for index, value in enumerate(values) if value in log]
assert not leaked, f"Keycloak logged {len(leaked)} sensitive test value(s); entries {leaked} of the evidence list"
for marker in ("type=\"LOGIN", "type=\"REGISTER", "type=\"RESET_PASSWORD", "org.keycloak.events"):
    assert marker not in log, f"Keycloak emitted user events to its log after alignment: {marker}"
print(f"Keycloak log ({len(log.splitlines())} lines) contains none of {len(values)} sensitive test values and no user events.")
PY

test_failed=false
printf '%s\n' 'Private-dev identity alignment, rollback and Mailpit/relay transports passed against disposable Keycloak 26.7.'
