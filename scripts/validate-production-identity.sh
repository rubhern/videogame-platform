#!/usr/bin/env bash

# Proves the public-production realm policy against disposable Keycloak, PostgreSQL and
# Mailpit on an internal Docker network. Nothing is published to the host, no real SMTP
# provider is contacted and no local or private-dev configuration or data is used.
#
# --inspect skips the automated journey and instead serves the same disposable stack on
# host loopback only (Keycloak https://localhost:8443, Mailpit UI http://127.0.0.1:8025)
# for a manual browser check, until Enter removes it.

set -Eeuo pipefail

inspect=false
case "${1:-}" in
  "") ;;
  --inspect) inspect=true ;;
  *)
    echo "Usage: bash scripts/validate-production-identity.sh [--inspect]" >&2
    exit 2
    ;;
esac

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
playwright_image="mcr.microsoft.com/playwright@sha256:dcc5531e97840b9b5e794f2814476b21571c5124a3fca2267d73041f56e7580e"
postgres_image="postgres:18.4-bookworm"
keycloak_image="videogame-platform/keycloak-production-identity:26.7.0"
mailpit_image="$(sed -n 's/^ *image: //p' "$repository_root/deploy/private-dev/compose.mail.yaml")"
realm_import="$repository_root/deploy/public-production/keycloak/import"
run_id="${RANDOM}-$$"
network="videogame-platform-production-identity-${run_id}"
postgres_container="videogame-platform-production-identity-postgres-${run_id}"
keycloak_container="videogame-platform-production-identity-keycloak-${run_id}"
mailpit_container="videogame-platform-production-identity-mailpit-${run_id}"
identity_host="identity.gameometro.test"
mail_host="mail.gameometro.test"
application_host="gameometro.test"
identity_origin="https://$identity_host:8443"
network_options=(--internal)
keycloak_publish=()
mailpit_publish=()
mailpit_hosts="$mail_host:8025"
if [[ "$inspect" == "true" ]]; then
  # Loopback publishing needs a non-internal network; nothing binds beyond 127.0.0.1.
  identity_origin="https://localhost:8443"
  network_options=()
  keycloak_publish=(--publish 127.0.0.1:8443:8443)
  mailpit_publish=(--publish 127.0.0.1:8025:8025)
  mailpit_hosts="$mail_host:8025,127.0.0.1:8025,localhost:8025"
fi
run_dir="$(mktemp -d)"
test_failed=false

cleanup() {
  if [[ "$test_failed" == "true" ]]; then
    printf '%s\n' 'Keycloak diagnostics:' >&2
    docker logs "$keycloak_container" 2>&1 | tail -150 >&2 || true
    printf '%s\n' 'Mailpit diagnostics:' >&2
    docker logs "$mailpit_container" 2>&1 | tail -40 >&2 || true
  fi
  docker rm --force "$keycloak_container" "$mailpit_container" "$postgres_container" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf -- "$run_dir"
}

trap cleanup EXIT
trap 'test_failed=true' ERR

cd "$repository_root"

if ! docker info >/dev/null 2>&1; then
  echo "Docker must be running to prove the production identity policy." >&2
  exit 1
fi

python3 scripts/test-production-realm.py

random_secret() {
  od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
}

postgres_admin_password="$(random_secret)"
keycloak_database_password="$(random_secret)"
keycloak_admin_password="$(random_secret)"
bff_client_secret="$(random_secret)"
smtp_username="production-identity-${run_id}"
smtp_password="$(random_secret)"

# A throwaway CA signs the identity and mail-sink certificates, so Keycloak's HTTPS
# hostname and its STARTTLS submission are both verified rather than trusted blindly.
pki="$run_dir/pki"
mkdir -p "$pki" "$run_dir/evidence" "$run_dir/mail"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=Production identity test CA" \
  -keyout "$pki/ca.key" -out "$pki/ca.crt" >/dev/null 2>&1
for host in "$identity_host" "$mail_host"; do
  openssl req -newkey rsa:2048 -nodes -subj "/CN=$host" \
    -keyout "$pki/$host.key" -out "$pki/$host.csr" >/dev/null 2>&1
  printf 'subjectAltName=DNS:%s,DNS:localhost\nextendedKeyUsage=serverAuth\n' "$host" >"$pki/$host.ext"
  openssl x509 -req -in "$pki/$host.csr" -CA "$pki/ca.crt" -CAkey "$pki/ca.key" -CAcreateserial \
    -days 1 -extfile "$pki/$host.ext" -out "$pki/$host.crt" >/dev/null 2>&1
done
rm -f -- "$pki/ca.key" "$pki"/*.csr "$pki"/*.ext "$pki"/*.srl
# Disposable keys for containers running as unrelated users; the directory is removed on exit.
chmod 0755 "$pki" "$run_dir/mail"
chmod 0644 "$pki"/*
printf '%s:%s\n' "$smtp_username" "$smtp_password" >"$run_dir/mail/smtp-auth"
chmod 0644 "$run_dir/mail/smtp-auth"
chmod 0700 "$run_dir/evidence"

docker build --quiet --file deploy/private-dev/keycloak/Dockerfile --tag "$keycloak_image" . >/dev/null

docker network create "${network_options[@]}" "$network" >/dev/null

# The mail sink accepts only authenticated STARTTLS submission, like a real provider.
docker run --detach \
  --name "$mailpit_container" \
  --network "$network" \
  --network-alias "$mail_host" \
  "${mailpit_publish[@]}" \
  --user 65534:65534 \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=32m \
  --cap-drop ALL \
  --volume "$pki:/run/pki:ro" \
  --volume "$run_dir/mail:/run/mail:ro" \
  "$mailpit_image" \
  --max=100 --smtp-disable-rdns --disable-version-check \
  --allowed-hosts="$mailpit_hosts" \
  --smtp=0.0.0.0:587 \
  --smtp-tls-cert="/run/pki/$mail_host.crt" --smtp-tls-key="/run/pki/$mail_host.key" \
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

# Only the production import directory is mounted: no shared realm or synthetic user file.
docker run --detach \
  --name "$keycloak_container" \
  --network "$network" \
  --network-alias "$identity_host" \
  "${keycloak_publish[@]}" \
  --env KC_DB_URL=jdbc:postgresql://postgres:5432/videogame_keycloak \
  --env KC_DB_USERNAME=videogame_keycloak \
  --env KC_DB_PASSWORD="$keycloak_database_password" \
  --env KC_BOOTSTRAP_ADMIN_USERNAME=production-identity-admin \
  --env KC_BOOTSTRAP_ADMIN_PASSWORD="$keycloak_admin_password" \
  --env KC_HOSTNAME="$identity_origin" \
  --env KC_HTTPS_PORT=8443 \
  --env KC_HTTP_ENABLED=false \
  --env KC_HTTPS_CERTIFICATE_FILE="/run/pki/$identity_host.crt" \
  --env KC_HTTPS_CERTIFICATE_KEY_FILE="/run/pki/$identity_host.key" \
  --env KC_TRUSTSTORE_PATHS=/run/pki/ca.crt \
  --env KC_LOG_LEVEL=info \
  --env APPLICATION_PUBLIC_HOST="$application_host" \
  --env APPLICATION_PUBLIC_ORIGIN="https://$application_host" \
  --env KEYCLOAK_BFF_CLIENT_SECRET="$bff_client_secret" \
  --env KEYCLOAK_SMTP_HOST="$mail_host" \
  --env KEYCLOAK_SMTP_PORT=587 \
  --env KEYCLOAK_SMTP_FROM="no-reply@$application_host" \
  --env KEYCLOAK_SMTP_USER="$smtp_username" \
  --env KEYCLOAK_SMTP_PASSWORD="$smtp_password" \
  --volume "$pki:/run/pki:ro" \
  --volume "$realm_import:/opt/keycloak/data/import:ro" \
  "$keycloak_image" start --optimized --import-realm >/dev/null

run_python() {
  docker run --rm \
    --network "$network" \
    --user "$(id -u):$(id -g)" \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,size=16m \
    --env KEYCLOAK_TEST_ORIGIN="https://$identity_host:8443" \
    --env KEYCLOAK_TEST_CA_FILE=/run/pki/ca.crt \
    --env KEYCLOAK_TEST_ADMIN_USERNAME=production-identity-admin \
    --env KEYCLOAK_TEST_ADMIN_PASSWORD="$keycloak_admin_password" \
    --env KEYCLOAK_TEST_CLIENT_SECRET="$bff_client_secret" \
    --env APPLICATION_TEST_HOST="$application_host" \
    --env MAILPIT_API_ORIGIN="http://$mail_host:8025" \
    --env MAIL_TEST_SENDER="no-reply@$application_host" \
    --env TEST_EVIDENCE_DIR=/run/evidence \
    --volume "$pki:/run/pki:ro" \
    --volume "$run_dir/evidence:/run/evidence:rw" \
    --volume "$repository_root:/work:ro" \
    --workdir /work \
    "$playwright_image" \
    python3 "$@"
}

ready=false
for _ in $(seq 1 120); do
  if run_python -c 'import os, ssl, urllib.request; urllib.request.urlopen(os.environ["KEYCLOAK_TEST_ORIGIN"] + "/realms/gameometro/.well-known/openid-configuration", context=ssl.create_default_context(cafile=os.environ["KEYCLOAK_TEST_CA_FILE"]), timeout=5)' >/dev/null 2>&1; then
    ready=true
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$keycloak_container" 2>/dev/null || true)" != "true" ]]; then
    echo "Keycloak exited before the production realm became ready." >&2
    test_failed=true
    exit 1
  fi
  sleep 1
done
if [[ "$ready" != "true" ]]; then
  echo "Keycloak 26.7 did not serve the production realm over verified HTTPS." >&2
  test_failed=true
  exit 1
fi

if [[ "$inspect" == "true" ]]; then
  challenge="$(random_secret | tr -d '\n' | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
  cat <<INSPECT

Disposable production realm on host loopback; its test CA is $pki/ca.crt.
Registration: $identity_origin/realms/gameometro/protocol/openid-connect/registrations?client_id=videogame-platform-bff&response_type=code&scope=openid&redirect_uri=https%3A%2F%2F$application_host%2Flogin%2Foauth2%2Fcode%2Fkeycloak&state=inspect&code_challenge=$challenge&code_challenge_method=S256
Mailpit:      http://127.0.0.1:8025
A successful journey ends at https://$application_host/login/oauth2/code/keycloak?...&code=...,
which does not load because this stack has no BFF.
INSPECT
  read -r -p "Press Enter to remove the disposable stack. " _ || true
  test_failed=false
  exit 0
fi

run_python scripts/test-production-identity-keycloak.py

# Credential material, addresses and the client network address must never reach the log.
docker logs "$keycloak_container" >"$run_dir/keycloak.log" 2>&1
printf '%s\n' "$bff_client_secret" "$smtp_password" "$keycloak_admin_password" "$keycloak_database_password" \
  >>"$run_dir/evidence/sensitive-values.txt"
python3 - "$run_dir/keycloak.log" "$run_dir/evidence/sensitive-values.txt" "$run_dir/evidence/consumed-codes.txt" <<'PY'
import pathlib, sys

def lines(path):
    return [value for value in pathlib.Path(path).read_text(encoding="utf-8").splitlines() if value]

log = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
values = lines(sys.argv[2])
assert len(values) >= 20, "the journey recorded too few sensitive values to make the log check meaningful"
leaked = [index for index, value in enumerate(values) if value in log]
assert not leaked, f"Keycloak logged {len(leaked)} sensitive test value(s); entries {leaked} of the evidence list"
# Keycloak names a replayed code only after consuming it and revoking what it issued.
for code in lines(sys.argv[3]):
    for line in log.splitlines():
        assert code not in line or "already used" in line, "a consumed code appeared outside the replay warning"
for marker in ("type=\"LOGIN", "type=LOGIN", "type=\"REGISTER", "type=REGISTER", "org.keycloak.events"):
    assert marker not in log, f"Keycloak emitted user events to its log: {marker}"
print(f"Keycloak log ({len(log.splitlines())} lines) contains none of {len(values)} sensitive test values and no user events.")
PY

test_failed=false
printf '%s\n' 'Production realm policy passed against disposable Keycloak 26.7, PostgreSQL and authenticated STARTTLS Mailpit.'
