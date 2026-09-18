#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repository_root/deploy/private-dev/compose.yaml"
runtime_env=""
runtime_env_supplied=false
live=false
telemetry_smoke=false
temporary_directory=""
smoke_started=false
smoke_compose=()

cleanup() {
  if [[ "$smoke_started" == true ]]; then
    "${smoke_compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  if [[ -n "$temporary_directory" && -d "$temporary_directory" ]]; then
    rm -rf -- "$temporary_directory"
  fi
}
trap cleanup EXIT

usage() {
  cat <<'EOF'
Usage:
  validate-private-dev-runtime.sh
  validate-private-dev-runtime.sh --telemetry-smoke
  validate-private-dev-runtime.sh --env-file <protected-runtime.env> [--live]

Without arguments, validates the reviewed configuration with disposable placeholder
inputs and does not start containers. --telemetry-smoke starts only a disposable
collector, submits one fixed span and one fixed metric through its internal Docker
network, then removes it. --live inspects an already-started host stack; it never creates,
restarts, or removes services.
EOF
}

while (($# > 0)); do
  case "$1" in
    --env-file)
      runtime_env="${2:-}"
      runtime_env_supplied=true
      shift 2
      ;;
    --live)
      live=true
      shift
      ;;
    --telemetry-smoke)
      telemetry_smoke=true
      shift
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "$live" == true && -z "$runtime_env" ]]; then
  printf '%s\n' '--live requires --env-file.' >&2
  exit 2
fi
if [[ "$live" == true && "$telemetry_smoke" == true ]]; then
  printf '%s\n' '--live and --telemetry-smoke are mutually exclusive.' >&2
  exit 2
fi

if [[ -z "$runtime_env" ]]; then
  temporary_directory="$(mktemp -d)"
  secrets_directory="$temporary_directory/secrets"
  mkdir -m 0750 "$secrets_directory"
  for name in \
    postgres-admin-password \
    application-db-password \
    application-migration-db-password \
    keycloak-db-password \
    keycloak-admin-password \
    keycloak-bff-client-secret \
    igdb-client-id \
    igdb-client-secret \
    oidc-smoke-username \
    oidc-smoke-password; do
    printf 'static-validation-%s\n' "$name" >"$secrets_directory/$name"
    chmod 0644 "$secrets_directory/$name"
  done
  runtime_env="$temporary_directory/runtime.env"
  cat >"$runtime_env" <<EOF
COMPOSE_PROJECT_NAME=videogame-platform-dev-validation-$$
PRIVATE_DEV_SECRETS_DIR=$secrets_directory
PRIVATE_DEV_SECRETS_GID=$(id -g)
PRIVATE_DEV_APPLICATION_ORIGIN=https://vgpdev.validation.invalid
PRIVATE_DEV_KEYCLOAK_ORIGIN=https://vgpdev.validation.invalid:8443
APPLICATION_LOOPBACK_PORT=8080
KEYCLOAK_LOOPBACK_PORT=8180
KEYCLOAK_ADMIN_USERNAME=validation-admin
APPLICATION_IMAGE=ghcr.io/rubhern/videogame-platform@sha256:1111111111111111111111111111111111111111111111111111111111111111
APPLICATION_VERSION=0.0.0-validation
SOURCE_REVISION=1111111111111111111111111111111111111111
SMOKE_CORRELATION_ID=deployment-smoke-validation
SMOKE_TRACE_ID=11111111111111111111111111111111
EOF
else
  runtime_env="$(realpath -- "$runtime_env")"
  [[ -f "$runtime_env" ]] || {
    printf 'Runtime environment file does not exist: %s\n' "$runtime_env" >&2
    exit 1
  }
  if grep -Eq 'example-tailnet' "$runtime_env"; then
    printf 'Runtime environment still contains example private origins: %s\n' "$runtime_env" >&2
    exit 1
  fi
  temporary_directory="$(mktemp -d)"
fi

rendered_config="$temporary_directory/compose.json"
docker compose \
  --env-file "$runtime_env" \
  --file "$compose_file" \
  --profile application \
  --profile deployment \
  config --format json >"$rendered_config"

python3 - "$rendered_config" <<'PY'
import json
import pathlib
import stat
import sys

config = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
services = config["services"]
expected_services = {
    "postgres",
    "keycloak",
    "telemetry",
    "application",
    "migration",
    "deployment-smoke",
}
assert set(services) == expected_services, f"unexpected services: {set(services)}"

expected_secret_access = {
    "postgres": {
        "postgres_admin_password",
        "application_db_password",
        "application_migration_db_password",
        "keycloak_db_password",
    },
    "keycloak": {"keycloak_db_password", "keycloak_admin_password", "keycloak_bff_client_secret"},
    "telemetry": set(),
    "application": {"application_db_password", "keycloak_bff_client_secret", "igdb_client_id", "igdb_client_secret"},
    "migration": {"application_migration_db_password"},
    "deployment-smoke": {"oidc_smoke_username", "oidc_smoke_password"},
}

for service_name, service in services.items():
    expected_restart = "no" if service_name in {"migration", "deployment-smoke"} else "unless-stopped"
    assert service.get("restart") == expected_restart, f"{service_name} restart policy"
    assert int(service.get("mem_limit", 0)) > 0, f"{service_name} memory limit"
    assert float(service.get("cpus", 0)) > 0, f"{service_name} CPU limit"
    actual_secrets = {secret["source"] for secret in service.get("secrets") or []}
    assert actual_secrets == expected_secret_access[service_name], f"{service_name} secret grants"
    environment = service.get("environment") or {}
    forbidden = {
        "POSTGRES_PASSWORD",
        "APPLICATION_DB_PASSWORD",
        "APPLICATION_MIGRATION_DB_PASSWORD",
        "KEYCLOAK_DB_PASSWORD",
        "KC_DB_PASSWORD",
        "KC_BOOTSTRAP_ADMIN_PASSWORD",
        "KEYCLOAK_BFF_CLIENT_SECRET",
        "IGDB_CLIENT_ID",
        "IGDB_CLIENT_SECRET",
        "OIDC_SMOKE_USERNAME",
        "OIDC_SMOKE_PASSWORD",
    }
    assert forbidden.isdisjoint(environment), f"{service_name} exposes secret values through Compose environment"

published = []
for service_name, service in services.items():
    for port in service.get("ports") or []:
        published.append((service_name, port.get("host_ip"), int(port["published"]), int(port["target"])))
assert sorted(published) == [
    ("application", "127.0.0.1", 8080, 8080),
    ("keycloak", "127.0.0.1", 8180, 8080),
], f"unexpected published ports: {published}"
assert all(host_ip == "127.0.0.1" for _, host_ip, _, _ in published), (
    f"IPv4/IPv6 exposure boundary requires explicit IPv4 loopback binds: {published}"
)

assert config["networks"]["data"]["internal"] is True
assert config["networks"]["telemetry"]["internal"] is True
assert "ports" not in services["postgres"]
assert "ports" not in services["telemetry"]
assert services["application"]["environment"]["APPLICATION_FLYWAY_ENABLED"] == "false"
assert services["application"]["environment"]["APPLICATION_SESSION_COOKIE_NAME"] == "__Host-vgp_session"
assert services["application"]["environment"]["APPLICATION_SESSION_COOKIE_SECURE"] == "true"
assert services["application"]["environment"]["TELEMETRY_DEPLOYMENT_ENVIRONMENT"] == "dev"
assert services["application"]["environment"]["TELEMETRY_SERVICE_VERSION"]
keycloak_origin = services["keycloak"]["environment"]["KC_HOSTNAME"].rstrip("/")
expected_issuer = f"{keycloak_origin}/realms/videogame-platform"
application_oidc = services["application"]["environment"]
assert application_oidc["OIDC_ISSUER_URI"] == expected_issuer, "application issuer must match Keycloak's external hostname"
assert application_oidc["OIDC_AUTHORIZATION_URI"] == f"{expected_issuer}/protocol/openid-connect/auth"
assert application_oidc["OIDC_TOKEN_URI"] == "http://keycloak:8080/realms/videogame-platform/protocol/openid-connect/token"
assert application_oidc["OIDC_JWK_SET_URI"] == "http://keycloak:8080/realms/videogame-platform/protocol/openid-connect/certs"
assert application_oidc["OIDC_USER_INFO_URI"] == "http://keycloak:8080/realms/videogame-platform/protocol/openid-connect/userinfo"
assert services["migration"]["environment"] == {
    "APPLICATION_MIGRATION_DB_PASSWORD_FILE": "/run/secrets/application_migration_db_password",
    "APPLICATION_MIGRATION_DB_URL": "jdbc:postgresql://postgres:5432/videogame_platform",
    "APPLICATION_MIGRATION_DB_USERNAME": "videogame_app_migrator",
}
assert services["migration"]["networks"] == {"data": None}
assert services["deployment-smoke"]["environment"]["EXPECTED_APPLICATION_VERSION"]
assert services["deployment-smoke"]["environment"]["EXPECTED_SOURCE_REVISION"]
assert int(services["deployment-smoke"]["shm_size"]) == 256 * 1024 * 1024

keycloak_imports = {
    (volume.get("source"), volume.get("target"))
    for volume in services["keycloak"].get("volumes") or []
    if volume.get("target", "").startswith("/opt/keycloak/data/import/")
}
assert len(keycloak_imports) == 1, f"private dev must mount exactly one realm import: {keycloak_imports}"
realm_source, realm_target = next(iter(keycloak_imports))
assert realm_source.endswith("/docker/keycloak/import/videogame-platform-realm.json")
assert realm_target == "/opt/keycloak/data/import/videogame-platform-realm.json"

for service_name in ("keycloak", "telemetry", "application", "migration", "deployment-smoke"):
    assert services[service_name].get("read_only") is True, f"{service_name} root filesystem"
    assert services[service_name].get("cap_drop") == ["ALL"], f"{service_name} capabilities"

for service_name in ("postgres", "telemetry"):
    assert "@sha256:" in services[service_name]["image"], f"{service_name} image is not digest-pinned"
for service_name in ("application", "migration"):
    assert "@sha256:" in services[service_name]["image"], f"{service_name} image is not digest-pinned"
assert services["application"]["image"] == services["migration"]["image"]
assert services["keycloak"].get("build"), "Keycloak optimized image build is missing"
assert services["deployment-smoke"].get("build"), "deployment smoke image build is missing"

secret_directories = set()
for secret_name, definition in config["secrets"].items():
    source = pathlib.Path(definition["file"])
    assert source.is_file(), f"missing secret source for {secret_name}: {source}"
    mode = stat.S_IMODE(source.stat().st_mode)
    assert mode & 0o022 == 0, f"secret is writable by group/other: {source} ({mode:o})"
    secret_directories.add(source.parent)
for directory in secret_directories:
    mode = stat.S_IMODE(directory.stat().st_mode)
    assert mode & 0o007 == 0, f"secret directory is accessible to other users: {directory} ({mode:o})"

print("Private-dev Compose topology, isolation, secret grants, limits and restart policies passed.")
PY

grep -q 'verbosity: basic' "$repository_root/deploy/private-dev/otel/collector.yaml"
grep -q 'send_batch_max_size: 1024' "$repository_root/deploy/private-dev/otel/collector.yaml"
grep -q 'service.version:' "$repository_root/backend/src/main/resources/application.yaml"
grep -q 'deployment.environment.name:' "$repository_root/backend/src/main/resources/application.yaml"
bash -n "$repository_root/deploy/private-dev/bin/prepare-secrets"
bash -n "$repository_root/deploy/private-dev/bin/deploy-private-dev"
bash -n "$repository_root/deploy/private-dev/bin/run-keycloak"
sh -n "$repository_root/deploy/private-dev/bin/run-application"
sh -n "$repository_root/deploy/private-dev/bin/run-migrations"
python3 -c 'import pathlib, sys; compile(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"), sys.argv[1], "exec")' \
  "$repository_root/deploy/private-dev/bin/provision-oidc-smoke-user"
if [[ "$runtime_env_supplied" == false && "$telemetry_smoke" == false ]]; then
  node --check "$repository_root/deploy/private-dev/smoke/deployment-smoke.mjs"
  node --test \
    "$repository_root/deploy/private-dev/smoke/releases-outcome.test.mjs" \
    "$repository_root/deploy/private-dev/smoke/deployment-smoke-order.test.mjs" \
    "$repository_root/deploy/private-dev/smoke/deployment-smoke-contract.test.mjs"
  bash "$repository_root/scripts/test-private-dev-deployment.sh"
  python3 "$repository_root/scripts/test-private-dev-oidc-provisioning.py"
fi
python3 -m json.tool "$repository_root/docker/keycloak/import/videogame-platform-realm.json" >/dev/null
python3 -m json.tool "$repository_root/docker/keycloak/import/videogame-platform-users-0.json" >/dev/null
[[ ! -e "$repository_root/deploy/private-dev/keycloak/videogame-platform-realm.json" ]]
bash -n "$repository_root/docker/postgres/init/001-create-databases.sh"
grep -Eq '^ARG KEYCLOAK_IMAGE=.*@sha256:[0-9a-f]{64}$' "$repository_root/deploy/private-dev/keycloak/Dockerfile"
grep -Eq '^FROM mcr\.microsoft\.com/playwright@sha256:[0-9a-f]{64}$' "$repository_root/deploy/private-dev/smoke/Dockerfile"
python3 - "$repository_root/deploy/private-dev/otel/synthetic" <<'PY'
import json
import pathlib
import sys

fixtures = pathlib.Path(sys.argv[1])
trace = json.loads((fixtures / "trace.json").read_text(encoding="utf-8"))
metric = json.loads((fixtures / "metric.json").read_text(encoding="utf-8"))
assert len(trace["resourceSpans"]) == 1
assert len(trace["resourceSpans"][0]["scopeSpans"][0]["spans"]) == 1
assert len(metric["resourceMetrics"]) == 1
assert len(metric["resourceMetrics"][0]["scopeMetrics"][0]["metrics"]) == 1
for signal in (trace["resourceSpans"][0], metric["resourceMetrics"][0]):
    attributes = {
        item["key"]: item["value"]["stringValue"]
        for item in signal["resource"]["attributes"]
    }
    assert attributes == {
        "service.name": "issue-43-synthetic",
        "service.version": "43.0.0-synthetic",
        "deployment.environment.name": "validation",
    }
PY

if [[ "$telemetry_smoke" == true ]]; then
  smoke_compose=(
    docker compose
    --env-file "$runtime_env"
    --file "$compose_file"
    --file "$repository_root/deploy/private-dev/compose.telemetry-smoke.yaml"
  )
  "${smoke_compose[@]}" up --detach telemetry
  smoke_started=true
  "${smoke_compose[@]}" run --rm telemetry-smoke

  for _ in {1..20}; do
    telemetry_logs="$("${smoke_compose[@]}" logs --no-color telemetry)"
    if grep -q '"otelcol.signal": "traces"' <<<"$telemetry_logs" &&
        grep -q '"resource spans": 1, "spans": 1' <<<"$telemetry_logs" &&
        grep -q '"otelcol.signal": "metrics"' <<<"$telemetry_logs" &&
        grep -q '"resource metrics": 1, "metrics": 1, "data points": 1' <<<"$telemetry_logs"; then
      break
    fi
    sleep 0.25
  done
  if ! grep -q '"resource spans": 1, "spans": 1' <<<"$telemetry_logs" ||
      ! grep -q '"resource metrics": 1, "metrics": 1, "data points": 1' <<<"$telemetry_logs"; then
    printf 'Collector logs did not confirm both synthetic signal types:\n%s\n' "$telemetry_logs" >&2
    exit 1
  fi
  if grep -Eq '43\.0\.0-synthetic|bounded-otlp-receipt|issue43\.synthetic\.receipt' <<<"$telemetry_logs"; then
    printf 'Basic collector logs exposed synthetic signal contents.\n' >&2
    exit 1
  fi
  printf 'Bounded synthetic OTLP receipt passed: one versioned span and one metric; signal contents were absent from collector logs.\n'
fi

if [[ "$live" == false ]]; then
  if [[ "$telemetry_smoke" == true ]]; then
    printf 'Private-dev validation passed; the disposable collector was removed and host settings were unchanged.\n'
  else
    printf 'Private-dev static validation passed; no containers or host settings were changed.\n'
  fi
  exit 0
fi

compose=(docker compose --env-file "$runtime_env" --file "$compose_file")
required_services=(postgres keycloak telemetry)
for service_name in "${required_services[@]}"; do
  container_id="$("${compose[@]}" ps --quiet "$service_name")"
  [[ -n "$container_id" ]] || {
    printf 'Required service is not created: %s\n' "$service_name" >&2
    exit 1
  }
  state="$(docker inspect --format '{{.State.Status}}' "$container_id")"
  [[ "$state" == running ]] || {
    printf '%s is not running: %s\n' "$service_name" "$state" >&2
    exit 1
  }
  restart_policy="$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$container_id")"
  [[ "$restart_policy" == unless-stopped ]] || {
    printf '%s has unexpected restart policy: %s\n' "$service_name" "$restart_policy" >&2
    exit 1
  }
done

while IFS= read -r container_id; do
  [[ -n "$container_id" ]] || continue
  if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container_id" |
      grep -Eq '^(POSTGRES_PASSWORD|APPLICATION_DB_PASSWORD|APPLICATION_MIGRATION_DB_PASSWORD|KEYCLOAK_DB_PASSWORD|KC_DB_PASSWORD|KC_BOOTSTRAP_ADMIN_PASSWORD|KEYCLOAK_BFF_CLIENT_SECRET|IGDB_CLIENT_ID|IGDB_CLIENT_SECRET)='; then
    printf 'Container metadata contains a forbidden secret environment variable: %s\n' "$container_id" >&2
    exit 1
  fi
done < <("${compose[@]}" ps --quiet)

printf '\nCurrent bounded service resource snapshot (capture after idle and representative load):\n'
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.PIDs}}' $("${compose[@]}" ps --quiet)
printf '\nHost pressure snapshot:\n'
uptime
free -h
df -h /
printf '\nListening TCP sockets (review every non-loopback listener):\n'
printf 'IPv4:\n'
ss -4 -lnt
printf 'IPv6:\n'
ss -6 -lnt

assert_no_host_listener() {
  local port="$1"
  if ss -H -lnt "sport = :$port" | grep -q .; then
    printf 'Protected port has an unexpected IPv4 or IPv6 host listener: %s\n' "$port" >&2
    ss -H -lnt "sport = :$port" >&2
    exit 1
  fi
}

assert_ipv4_loopback_only() {
  local port="$1"
  local required="$2"
  local ipv4_listeners ipv6_listeners
  ipv4_listeners="$(ss -H -4 -lnt "sport = :$port")"
  ipv6_listeners="$(ss -H -6 -lnt "sport = :$port")"
  if [[ "$required" == true && -z "$ipv4_listeners" ]]; then
    printf 'Expected IPv4 loopback listener is absent on port %s.\n' "$port" >&2
    exit 1
  fi
  if [[ -n "$ipv4_listeners" ]] && awk -v port="$port" '$4 != "127.0.0.1:" port { exit 1 }' <<<"$ipv4_listeners"; then
    :
  elif [[ -n "$ipv4_listeners" ]]; then
    printf 'Port %s has a non-loopback IPv4 listener.\n' "$port" >&2
    exit 1
  fi
  if [[ -n "$ipv6_listeners" ]]; then
    printf 'Port %s has an unexpected IPv6 listener.\n' "$port" >&2
    exit 1
  fi
}

assert_ipv4_loopback_only "${KEYCLOAK_LOOPBACK_PORT:-8180}" true
assert_ipv4_loopback_only "${APPLICATION_LOOPBACK_PORT:-8080}" false
for unpublished_port in 5432 4317 4318 8081 9000; do
  assert_no_host_listener "$unpublished_port"
done
printf '\nTailscale Serve state (must say available within the tailnet):\n'
tailscale serve status
printf '\nPrivate-dev live inspection passed. Router/Internet isolation and reboot persistence still require external manual evidence.\n'
