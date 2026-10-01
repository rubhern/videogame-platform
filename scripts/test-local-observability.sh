#!/usr/bin/env bash
set -Eeuo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_directory="$(mktemp -d)"
smoke_started=false
smoke_compose=()
cleanup() {
  if [[ "$smoke_started" == true ]]; then
    "${smoke_compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  rm -rf -- "$temporary_directory"
}
trap cleanup EXIT
fixture="$temporary_directory/repository"
mkdir -p "$fixture/scripts" "$fixture/backend" "$temporary_directory/bin"
cp "$repository_root/scripts/"{local-dependencies.sh,backend-artifact.sh} "$fixture/scripts/"
cp "$repository_root/pom.xml" "$fixture/"
cat >"$temporary_directory/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$LOCAL_METRICS_COMMAND_LOG"
DOCKER
chmod +x "$temporary_directory/bin/docker"
export LOCAL_METRICS_COMMAND_LOG="$temporary_directory/commands"
run() {
  PATH="$temporary_directory/bin:$PATH" bash "$fixture/scripts/local-dependencies.sh" "$@" >/dev/null
}
run up
[[ ! -e "$fixture/.local-secrets" ]]
! grep -q 'compose.observability.yaml' "$LOCAL_METRICS_COMMAND_LOG"
initial_env="$(sha256sum "$fixture/.env" "$fixture/backend/.env")"
run observability
[[ -s "$fixture/.local-secrets/grafana-admin-password" ]]
[[ "$(stat -c %a "$fixture/.local-secrets")" == 700 ]]
[[ "$(stat -c %a "$fixture/.local-secrets/grafana-admin-password")" == 644 ]]
initial_secret="$(sha256sum "$fixture/.local-secrets/grafana-admin-password")"
run application --observability
grep -q 'compose.observability.yaml --profile observability --profile full up --build' "$LOCAL_METRICS_COMMAND_LOG"
run up --observability
[[ "$(sha256sum "$fixture/.env" "$fixture/backend/.env")" == "$initial_env" ]]
[[ "$(sha256sum "$fixture/.local-secrets/grafana-admin-password")" == "$initial_secret" ]]
run down
grep -q 'compose.observability.yaml --profile observability down --remove-orphans' "$LOCAL_METRICS_COMMAND_LOG"
run reset --yes
grep -q 'compose.observability.yaml --profile observability down --volumes --remove-orphans' "$LOCAL_METRICS_COMMAND_LOG"
[[ -s "$fixture/.local-secrets/grafana-admin-password" ]]
sed -i 's/COMPOSE_PROJECT_NAME=videogame-platform/COMPOSE_PROJECT_NAME=other-project/' "$fixture/.env"
if run reset --yes 2>/dev/null; then
  printf 'Local reset accepted an unrelated Compose project.\n' >&2
  exit 1
fi
printf 'Local metrics lifecycle, opt-in, secret preservation and reset boundary passed.\n'
[[ "${1:-}" == --lifecycle-only ]] && exit 0

# Real Compose rendering, no Docker daemon or containers required.
cp "$fixture/.env" "$temporary_directory/local.env"
sed -i 's/COMPOSE_PROJECT_NAME=other-project/COMPOSE_PROJECT_NAME=videogame-platform/' "$temporary_directory/local.env"
APPLICATION_VERSION=validation docker compose --env-file "$temporary_directory/local.env" \
  --file "$repository_root/compose.yaml" --file "$repository_root/compose.observability.yaml" \
  --profile full --profile observability config --format json >"$temporary_directory/local.json"
python3 - "$temporary_directory/local.json" "$repository_root" <<'PY'
import json
import pathlib
import sys
config = json.loads(pathlib.Path(sys.argv[1]).read_text())
root = pathlib.Path(sys.argv[2])
services = config['services']
assert set(services) == {'postgres', 'keycloak', 'application', 'telemetry', 'prometheus', 'grafana'}
assert config['networks']['telemetry']['internal']
for name in ('telemetry', 'prometheus', 'grafana'):
    service = services[name]
    assert '@sha256:' in service['image']
    assert int(service['mem_limit']) > 0 and float(service['cpus']) > 0 and int(service['pids_limit']) > 0
    assert service['logging']['options'] == {'max-size': '10m', 'max-file': '3'}
    assert service['read_only'] and service['cap_drop'] == ['ALL']
    for volume in service['volumes']:
        if volume['type'] == 'bind':
            assert pathlib.Path(volume['source']).exists(), volume['source']
            assert volume['read_only']
assert 'ports' not in services['prometheus']
for name, port in [('telemetry', 4318), ('grafana', 3000)]:
    ports = services[name]['ports']
    assert len(ports) == 1
    assert ports[0]['host_ip'] == '127.0.0.1' and int(ports[0]['published']) == port
assert services['application']['environment']['TELEMETRY_OTLP_METRICS_ENABLED'] == 'true'
assert services['application']['environment']['TELEMETRY_OTLP_METRICS_ENDPOINT'] == 'http://telemetry:4318/v1/metrics'
assert set(services['application']['depends_on']) == {'postgres', 'keycloak'}
assert set(services['application']['networks']) == {'default', 'telemetry'}
assert services['grafana']['environment']['GF_AUTH_ANONYMOUS_ENABLED'] == 'false'
assert pathlib.Path(config['secrets']['grafana_admin_password']['file']) == root / '.local-secrets/grafana-admin-password'
assert '--storage.tsdb.retention.time=7d' in services['prometheus']['command']
assert '--storage.tsdb.retention.size=512MiB' in services['prometheus']['command']
assert set(config['volumes']) == {'postgres-data', 'prometheus-data', 'grafana-data'}
print('Local Compose inheritance, mounts, isolation, OTLP wiring and retention passed.')
PY

[[ "${1:-}" == --smoke ]] || exit 0
# Only an explicitly disposable project receives this fixed synthetic transport probe.
export APPLICATION_VERSION=validation
cat >"$temporary_directory/secret.yaml" <<YAML
secrets:
  grafana_admin_password:
    file: $fixture/.local-secrets/grafana-admin-password
YAML
smoke_compose=(docker compose --env-file "$temporary_directory/local.env"
  --project-name "vgp-local-metrics-smoke-$$"
  --file "$repository_root/compose.yaml" --file "$repository_root/compose.observability.yaml"
  --file "$temporary_directory/secret.yaml" --profile observability)
"${smoke_compose[@]}" config --format json >"$temporary_directory/smoke.json"
smoke_started=true
"${smoke_compose[@]}" up --detach telemetry prometheus grafana
for _ in {1..30}; do
  if curl --fail --silent --output /dev/null --connect-timeout 1 --max-time 3 \
      --header 'Content-Type: application/json' \
      --data-binary "@$repository_root/deploy/private-dev/otel/synthetic/metric.json" \
      http://127.0.0.1:4318/v1/metrics; then
    break
  fi
  sleep 1
done
received=false
for _ in {1..30}; do
  response="$("${smoke_compose[@]}" exec -T prometheus wget -qO- \
    'http://127.0.0.1:9090/api/v1/query?query=issue43_synthetic_receipt_ratio' 2>/dev/null || true)"
  if python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["status"] == "success"; assert any(x["value"][1] == "1" for x in r["data"]["result"])' \
      <<<"$response" 2>/dev/null; then
    received=true
    break
  fi
  sleep 2
done
[[ "$received" == true ]] || { printf 'Local loopback OTLP did not reach Prometheus.\n' >&2; exit 1; }
[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 5 http://127.0.0.1:3000/api/search)" == 401 ]]
python3 "$repository_root/scripts/private-dev-metrics-check.py" --local \
  --env-file "$temporary_directory/local.env" --config "$temporary_directory/smoke.json"
printf 'Local loopback OTLP, Prometheus receipt and authenticated Grafana provisioning smoke passed.\n'
