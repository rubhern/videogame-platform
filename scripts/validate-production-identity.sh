#!/usr/bin/env bash

# Proves the public-production realm policy against disposable Keycloak, PostgreSQL and
# Mailpit on an internal Docker network, then the packaged BFF against it: one canonical issuer
# on separate synthetic HTTPS origins with implicit port 443, the startup refusal of an
# inconsistent configuration, and a browser journey that inventories cookies and storage.
# Nothing is published to the host, no real SMTP provider is contacted and no local or
# private-dev configuration or data is used.
#
# PRODUCTION_IDENTITY_REUSE_PACKAGE=true reuses the JAR that scripts/package-application.sh
# already built from the current tree (CI runs the private-dev identity gate first).
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
source "$repository_root/scripts/backend-artifact.sh"
java_image="eclipse-temurin@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112"
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
application_container="videogame-platform-production-identity-application-${run_id}"
# Synthetic reserved names: the public domain is not decided. Both services listen on 443 so
# the issuer and callback have the production shape, with no explicit port.
identity_host="identity.gameometro.test"
mail_host="mail.gameometro.test"
application_host="gameometro.test"
identity_origin="https://$identity_host"
application_origin="https://$application_host"
issuer="$identity_origin/realms/gameometro"
network_options=(--internal)
keycloak_publish=()
mailpit_publish=()
mailpit_hosts="$mail_host:8025"
if [[ "$inspect" == "true" ]]; then
  # Loopback publishing needs a non-internal network; nothing binds beyond 127.0.0.1.
  identity_origin="https://localhost:8443"
  network_options=()
  keycloak_publish=(--publish 127.0.0.1:8443:443)
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
    printf '%s\n' 'Application diagnostics:' >&2
    docker logs "$application_container" 2>&1 | tail -150 >&2 || true
  fi
  docker rm --force "$application_container" "$application_container-refused" "$keycloak_container" "$mailpit_container" "$postgres_container" >/dev/null 2>&1 || true
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

# The same candidate must keep private-dev admission opt-in through real Compose.
python3 scripts/test-api-admission-private-dev.py
python3 scripts/test-production-realm.py

if [[ "$inspect" != "true" ]]; then
  if [[ "${PRODUCTION_IDENTITY_REUSE_PACKAGE:-false}" != "true" ]]; then
    bash scripts/package-application.sh
  fi
  application_jar="$(resolve_backend_jar)"
fi

random_secret() {
  od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
}

postgres_admin_password="$(random_secret)"
application_password="$(random_secret)"
migration_password="$(random_secret)"
truststore_password="$(random_secret)"
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
for host in "$identity_host" "$mail_host" "$application_host"; do
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
# The BFF verifies the identity server's certificate against the same throwaway CA.
docker run --rm --user "$(id -u):$(id -g)" --network none --volume "$pki:/run/pki" "$java_image" \
  keytool -importcert -noprompt -alias production-identity-test-ca -file /run/pki/ca.crt \
  -keystore /run/pki/truststore.p12 -storetype PKCS12 -storepass "$truststore_password" >/dev/null
printf '%s:%s\n' "$smtp_username" "$smtp_password" >"$run_dir/mail/smtp-auth"
chmod 0644 "$run_dir/mail/smtp-auth"
chmod 0700 "$run_dir/evidence"

docker build --quiet --build-arg KEYCLOAK_EVENT_METRICS_USER_ENABLED=true --file deploy/private-dev/keycloak/Dockerfile --tag "$keycloak_image" . >/dev/null

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
  --env APPLICATION_DB_PASSWORD="$application_password" \
  --env APPLICATION_MIGRATION_DB_PASSWORD="$migration_password" \
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
  --sysctl net.ipv4.ip_unprivileged_port_start=0 \
  --env KC_DB_URL=jdbc:postgresql://postgres:5432/videogame_keycloak \
  --env KC_DB_USERNAME=videogame_keycloak \
  --env KC_DB_PASSWORD="$keycloak_database_password" \
  --env KC_BOOTSTRAP_ADMIN_USERNAME=production-identity-admin \
  --env KC_BOOTSTRAP_ADMIN_PASSWORD="$keycloak_admin_password" \
  --env KC_HOSTNAME="$identity_origin" \
  --env KC_HTTPS_PORT=443 \
  --env KC_HTTP_ENABLED=false \
  --env KC_PROXY_HEADERS=xforwarded \
  --env KC_HTTPS_CERTIFICATE_FILE="/run/pki/$identity_host.crt" \
  --env KC_HTTPS_CERTIFICATE_KEY_FILE="/run/pki/$identity_host.key" \
  --env KC_TRUSTSTORE_PATHS=/run/pki/ca.crt \
  --env KC_LOG_LEVEL=info \
  --env-file "$repository_root/deploy/public-production/keycloak/abuse.env" \
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
  docker run --rm --interactive \
    --network "$network" \
    --user "$(id -u):$(id -g)" \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,size=16m \
    --env KEYCLOAK_TEST_ORIGIN="$identity_origin" \
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

# The packaged BFF with the production contract: canonical issuer, explicit callback and
# post-logout address, host-only Secure cookie, and verified TLS to the identity server.
bff_environment=(
  --env SPRING_PROFILES_ACTIVE=oidc
  --env APPLICATION_DB_URL=jdbc:postgresql://postgres:5432/videogame_platform
  --env APPLICATION_DB_USERNAME=videogame_app
  --env APPLICATION_DB_PASSWORD="$application_password"
  --env APPLICATION_MIGRATION_DB_URL=jdbc:postgresql://postgres:5432/videogame_platform
  --env APPLICATION_MIGRATION_DB_USERNAME=videogame_app_migrator
  --env APPLICATION_MIGRATION_DB_PASSWORD="$migration_password"
  --env SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed
  --env KEYCLOAK_BFF_CLIENT_SECRET="$bff_client_secret"
  --env OIDC_ISSUER_URI="$issuer"
  --env OIDC_AUTHORIZATION_URI="$issuer/protocol/openid-connect/auth"
  --env OIDC_TOKEN_URI="$issuer/protocol/openid-connect/token"
  --env OIDC_JWK_SET_URI="$issuer/protocol/openid-connect/certs"
  --env OIDC_USER_INFO_URI="$issuer/protocol/openid-connect/userinfo"
  --env OIDC_END_SESSION_URI="$issuer/protocol/openid-connect/logout"
  --env APPLICATION_OIDC_REDIRECT_URI="$application_origin/login/oauth2/code/keycloak"
  --env APPLICATION_SESSION_COOKIE_NAME=__Host-vgp_session
  --env APPLICATION_SESSION_COOKIE_SECURE=true
  --env PLATFORM_HTTPSECURITY_HSTS_ENABLED=true
  --env SERVER_PORT=443
  --env SERVER_SSL_CERTIFICATE="file:/run/pki/$application_host.crt"
  --env SERVER_SSL_CERTIFICATE_PRIVATE_KEY="file:/run/pki/$application_host.key"
  --volume "$pki:/run/pki:ro"
  --volume "$application_jar:/application.jar:ro"
)
java_command=(java -Djavax.net.ssl.trustStore=/run/pki/truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12
  "-Djavax.net.ssl.trustStorePassword=$truststore_password" -jar /application.jar)

# A post-logout address left to the request's Host must stop the application before any sign-in.
refused_log="$run_dir/refused-application.log"
if timeout 180 docker run --rm --name "$application_container-refused" --network "$network" \
    "${bff_environment[@]}" --env APPLICATION_FLYWAY_ENABLED=false "$java_image" "${java_command[@]}" \
    >"$refused_log" 2>&1; then
  echo "The BFF started although its post-logout address was built from the request." >&2
  test_failed=true
  exit 1
fi
grep -q 'Public identity contract violated; refusing to start: APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI must be explicit, not built from the request' "$refused_log" || {
  tail -60 "$refused_log" >&2
  echo "The BFF did not refuse the inconsistent configuration with the contract message." >&2
  test_failed=true
  exit 1
}
printf '%s\n' 'The BFF refused to start with a post-logout address built from the request.'

docker run --detach \
  --name "$application_container" \
  --network "$network" \
  --network-alias "$application_host" \
  --sysctl net.ipv4.ip_unprivileged_port_start=0 \
  "${bff_environment[@]}" \
  --env APPLICATION_FLYWAY_ENABLED=true \
  --env APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI="$application_origin/" \
  "$java_image" "${java_command[@]}" >/dev/null

ready=false
for _ in $(seq 1 120); do
  if run_python -c 'import os, ssl, urllib.request; urllib.request.urlopen("https://'"$application_host"'/api/v1/session", context=ssl.create_default_context(cafile=os.environ["KEYCLOAK_TEST_CA_FILE"]), timeout=5)' >/dev/null 2>&1; then
    ready=true
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$application_container" 2>/dev/null || true)" != "true" ]]; then
    echo "The production-configured BFF exited before serving HTTPS." >&2
    test_failed=true
    exit 1
  fi
  sleep 1
done
if [[ "$ready" != "true" ]]; then
  echo "The production-configured BFF did not serve HTTPS." >&2
  test_failed=true
  exit 1
fi

# The same read-only check the real host runs before accepting accounts.
docker run --rm \
  --network "$network" \
  --user "$(id -u):$(id -g)" \
  --read-only \
  --env OIDC_ISSUER_URI="$issuer" \
  --env OIDC_AUTHORIZATION_URI="$issuer/protocol/openid-connect/auth" \
  --env OIDC_TOKEN_URI="$issuer/protocol/openid-connect/token" \
  --env OIDC_JWK_SET_URI="$issuer/protocol/openid-connect/certs" \
  --env OIDC_USER_INFO_URI="$issuer/protocol/openid-connect/userinfo" \
  --env OIDC_END_SESSION_URI="$issuer/protocol/openid-connect/logout" \
  --env APPLICATION_OIDC_REDIRECT_URI="$application_origin/login/oauth2/code/keycloak" \
  --env APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI="$application_origin/" \
  --env OIDC_CONTRACT_CA_FILE=/run/pki/ca.crt \
  --volume "$pki:/run/pki:ro" \
  --volume "$repository_root:/work:ro" \
  --workdir /work \
  "$playwright_image" \
  python3 scripts/check-oidc-issuer-contract.py

docker run --rm \
  --ipc=host \
  --network "$network" \
  --user "$(id -u):$(id -g)" \
  --env PLAYWRIGHT_BASE_URL="$application_origin" \
  --env PRODUCTION_IDENTITY_ORIGIN="$identity_origin" \
  --env MAILPIT_API_ORIGIN="http://$mail_host:8025" \
  --env COOKIE_INVENTORY_FILE=/run/evidence/cookie-inventory.json \
  --env SENSITIVE_VALUES_FILE=/run/evidence/sensitive-values.txt \
  --volume "$run_dir/evidence:/run/evidence:rw" \
  --volume "$repository_root:/work" \
  --workdir /work \
  "$playwright_image" \
  npm --prefix frontend run test:e2e -- tests/production-identity.spec.ts --reporter=line --trace=off \
  || browser_journey_failed=true
# Optional copy of the observed cookie and storage inventory for review, kept on failure too.
if [[ -n "${PRODUCTION_IDENTITY_COOKIE_INVENTORY:-}" && -f "$run_dir/evidence/cookie-inventory.json" ]]; then
  cp "$run_dir/evidence/cookie-inventory.json" "$PRODUCTION_IDENTITY_COOKIE_INVENTORY"
fi
if [[ "${browser_journey_failed:-false}" == "true" ]]; then
  echo "The production identity browser journey failed." >&2
  test_failed=true
  exit 1
fi

# Every stored rating belongs to the UserId derived from the canonical issuer and a realm subject.
docker exec --env PGPASSWORD="$application_password" "$postgres_container" \
  psql --host=127.0.0.1 --username=videogame_app --dbname=videogame_platform --no-align --tuples-only \
  --command="SELECT user_id FROM ratings.rating" >"$run_dir/evidence/rating-user-ids.txt"
run_python - <<'OWNERSHIP'
import hashlib, json, os, pathlib, ssl, urllib.parse, urllib.request, uuid

origin = os.environ["KEYCLOAK_TEST_ORIGIN"]
tls = ssl.create_default_context(cafile=os.environ["KEYCLOAK_TEST_CA_FILE"])
form = urllib.parse.urlencode({"client_id": "admin-cli", "grant_type": "password",
    "username": os.environ["KEYCLOAK_TEST_ADMIN_USERNAME"], "password": os.environ["KEYCLOAK_TEST_ADMIN_PASSWORD"]}).encode()
with urllib.request.urlopen(f"{origin}/realms/master/protocol/openid-connect/token", form, context=tls) as response:
    token = json.load(response)["access_token"]
users = urllib.request.Request(f"{origin}/admin/realms/gameometro/users?max=1000", headers={"Authorization": f"Bearer {token}"})
with urllib.request.urlopen(users, context=tls) as response:
    subjects = [user["id"] for user in json.load(response)]
issuer = f"{origin}/realms/gameometro"
# UserId.fromIssuerAndSubject: a name-based (version 3) UUID of "issuer\nsubject".
derived = {str(uuid.UUID(bytes=hashlib.md5(f"{issuer}\n{subject}".encode()).digest(), version=3)) for subject in subjects}
rated = [line for line in pathlib.Path(os.environ["TEST_EVIDENCE_DIR"], "rating-user-ids.txt").read_text().splitlines() if line]
assert len(rated) == 1, f"expected the one synthetic rating, found {len(rated)}"
assert set(rated) <= derived, "a rating is keyed by a UserId not derived from the canonical issuer"
print(f"The rating's UserId derives from {issuer} and one of {len(subjects)} realm subjects.")
OWNERSHIP

# Credential material, addresses and the client network address must never reach the logs.
docker logs "$keycloak_container" >"$run_dir/keycloak.log" 2>&1
docker logs "$application_container" >"$run_dir/application.log" 2>&1
printf '%s\n' "$bff_client_secret" "$smtp_password" "$keycloak_admin_password" "$keycloak_database_password" \
  "$application_password" "$migration_password" "$truststore_password" >>"$run_dir/evidence/sensitive-values.txt"
python3 - "$run_dir/keycloak.log" "$run_dir/evidence/sensitive-values.txt" "$run_dir/evidence/consumed-codes.txt" "$run_dir/application.log" <<'PY'
import pathlib, sys

def lines(path):
    return [value for value in pathlib.Path(path).read_text(encoding="utf-8").splitlines() if value]

log = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
values = lines(sys.argv[2])
assert len(values) >= 20, "the journey recorded too few sensitive values to make the log check meaningful"
leaked = [index for index, value in enumerate(values) if value in log]
assert not leaked, f"Keycloak logged {len(leaked)} sensitive test value(s); entries {leaked} of the evidence list"
application_log = pathlib.Path(sys.argv[4]).read_text(encoding="utf-8", errors="replace")
leaked = [index for index, value in enumerate(values) if value in application_log]
assert not leaked, f"The BFF logged {len(leaked)} sensitive test value(s); entries {leaked} of the evidence list"
# Keycloak names a replayed code only after consuming it and revoking what it issued.
for code in lines(sys.argv[3]):
    for line in log.splitlines():
        assert code not in line or "already used" in line, "a consumed code appeared outside the replay warning"
for marker in ("type=\"LOGIN", "type=LOGIN", "type=\"REGISTER", "type=REGISTER", "org.keycloak.events"):
    assert marker not in log, f"Keycloak emitted user events to its log: {marker}"
print(f"Keycloak and BFF logs ({len(log.splitlines())} and {len(application_log.splitlines())} lines) contain none of {len(values)} sensitive test values; Keycloak logs no user events.")
PY

test_failed=false
printf '%s\n' 'Production realm policy and the BFF issuer and cookie contract passed against disposable Keycloak 26.7, PostgreSQL and authenticated STARTTLS Mailpit.'
