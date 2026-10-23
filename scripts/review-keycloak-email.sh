#!/usr/bin/env bash
# Disposable email-only proof. No existing realm, SMTP provider or application is modified.
set -Eeuo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-after}"
[[ "$mode" == before || "$mode" == after || "$mode" == after-public ]] || { echo 'Usage: review-keycloak-email.sh [before|after|after-public]' >&2; exit 2; }
run_id="gm-email-review-${RANDOM}-$$"
work="$(mktemp -d)"
cleanup() {
  docker rm -f "$run_id-keycloak" "$run_id-mailpit" "$run_id-callback" >/dev/null 2>&1 || true
  docker network rm "$run_id" >/dev/null 2>&1 || true
  rm -rf -- "$work"
}
trap cleanup EXIT
mkdir -p "$work/import" "$work/themes"
cp "$root/docker/keycloak/import/videogame-platform-realm.json" "$work/import/"
if [[ "$mode" == before ]]; then
  git -C "$root" archive HEAD docker/keycloak/themes/gameometro | tar -x -C "$work"
  cp -r "$work/docker/keycloak/themes/gameometro" "$work/themes/"
else
  cp -r "$root/docker/keycloak/themes/gameometro" "$work/themes/"
fi
# Only generated synthetic secrets. Never print the env file or raw container logs.
python3 - "$work/runtime.env" <<'PY'
import secrets, sys
from pathlib import Path
Path(sys.argv[1]).write_text('\n'.join(f'{key}={secrets.token_hex(24)}' for key in (
    'KC_BOOTSTRAP_ADMIN_PASSWORD', 'KEYCLOAK_TEST_ADMIN_PASSWORD', 'KEYCLOAK_BFF_CLIENT_SECRET'))+'\n')
p=Path(sys.argv[1]); lines=p.read_text().splitlines(); lines[1]='KEYCLOAK_TEST_ADMIN_PASSWORD='+lines[0].split('=',1)[1]; p.write_text('\n'.join(lines)+'\n'); p.chmod(0o600)
PY
keycloak_image="$(sed -n 's/^ARG KEYCLOAK_IMAGE=//p' "$root/deploy/private-dev/keycloak/Dockerfile")"
mailpit_image="$(sed -n 's/^ *image: //p' "$root/deploy/private-dev/compose.mail.yaml")"
playwright_image="$(sed -n 's/^playwright_image="\(.*\)"/\1/p' "$root/scripts/validate-identity.sh")"
public_origin=http://keycloak:8080
keycloak_tls=()
keycloak_tls_mounts=()
if [[ "$mode" == after-public ]]; then
  # Reserved synthetic DNS, self-signed TLS and network alias, never public DNS or ingress.
  public_origin=https://email-preview.gameometro.example:8443
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=email-preview.gameometro.example \
    -addext subjectAltName=DNS:email-preview.gameometro.example \
    -keyout "$work/tls.key" -out "$work/tls.crt" >/dev/null 2>&1
  chmod 644 "$work/tls.key" "$work/tls.crt"
  keycloak_tls=(--https-certificate-file=/tmp/email-review.crt --https-certificate-key-file=/tmp/email-review.key)
  keycloak_tls_mounts=(--volume "$work/tls.crt:/tmp/email-review.crt:ro" --volume "$work/tls.key:/tmp/email-review.key:ro")
fi
docker network create --internal "$run_id" >/dev/null
# A callback fixture lets Chromium finish the native OIDC redirect. It exchanges no code
# and records no URL; BFF session semantics remain covered by the existing identity gate.
cat > "$work/callback.py" <<'PYTHON'
from http.server import BaseHTTPRequestHandler, HTTPServer
class Callback(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"Synthetic callback")
    def log_message(self, *args):
        pass
HTTPServer(("0.0.0.0", 8080), Callback).serve_forever()
PYTHON
docker run -d --name "$run_id-callback" --network "$run_id" --network-alias application \
  --log-driver none --volume "$work/callback.py:/callback.py:ro" \
  "$playwright_image" python3 /callback.py >/dev/null
docker run -d --name "$run_id-mailpit" --network "$run_id" --network-alias mailpit \
  --read-only --tmpfs /tmp:rw,noexec,nosuid,size=32m --cap-drop ALL --user 65534:65534 \
  "$mailpit_image" --max=100 --smtp-disable-rdns --disable-version-check --allowed-hosts=mailpit:8025 >/dev/null
docker run -d --name "$run_id-keycloak" --network "$run_id" --network-alias keycloak --network-alias email-preview.gameometro.example \
  --env-file "$work/runtime.env" --env KC_BOOTSTRAP_ADMIN_USERNAME=email-review-admin \
  --env "KC_HOSTNAME=$public_origin" --env APPLICATION_PUBLIC_ORIGIN=http://application:8080 \
  --volume "$work/import:/opt/keycloak/data/import:ro" \
  --volume "$work/themes/gameometro:/opt/keycloak/themes/gameometro:ro" \
  "${keycloak_tls_mounts[@]}" "$keycloak_image" start-dev --db=dev-file --import-realm "${keycloak_tls[@]}" >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$run_id-keycloak" bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080; printf "GET /realms/videogame-platform/.well-known/openid-configuration HTTP/1.0\r\n\r\n" >&3; head -1 <&3' 2>/dev/null | rg -q '200'; then break; fi
  sleep 1
done
mkdir -p "$root/frontend/test-results/transactional-email/$mode"
docker run --rm --ipc=host --network "$run_id" --user "$(id -u):$(id -g)" \
  --env-file "$work/runtime.env" --env EMAIL_REVIEW_MODE="$mode" \
  --env KEYCLOAK_TEST_ADMIN_USERNAME=email-review-admin --env KEYCLOAK_TEST_ORIGIN=http://keycloak:8080 \
  --env MAILPIT_API_ORIGIN=http://mailpit:8025 --env "KEYCLOAK_TEST_PUBLIC_ORIGIN=$public_origin" \
  --volume "$root:/work" --workdir /work "$playwright_image" \
  npm --prefix frontend run test:e2e -- tests/transactional-email.spec.ts --reporter=line --trace=off \
  --output="test-results/email-review-run-$mode"
