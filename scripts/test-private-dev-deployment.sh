#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
deployment_command="$repository_root/deploy/private-dev/bin/deploy-private-dev"
runtime_validator="$repository_root/scripts/validate-private-dev-runtime.sh"
temporary_directory="$(mktemp -d)"
fake_bin="$temporary_directory/bin"
runtime_env="$temporary_directory/runtime.env"
secrets_directory="$temporary_directory/secrets"
command_log="$temporary_directory/docker-commands.log"
node_invocation_marker="$temporary_directory/node-was-invoked"
digest="sha256:1111111111111111111111111111111111111111111111111111111111111111"
source_revision="2222222222222222222222222222222222222222"
image="ghcr.io/rubhern/videogame-platform@$digest"
lock_file="/run/lock/videogame-platform-dev-deployment.lock"

cleanup() {
  rm -rf -- "$temporary_directory"
}
trap cleanup EXIT

mkdir -p "$fake_bin" "$secrets_directory"
chmod 0750 "$secrets_directory"
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
  printf 'test-%s\n' "$name" >"$secrets_directory/$name"
done
cat >"$runtime_env" <<EOF
PRIVATE_DEV_APPLICATION_ORIGIN=https://vgpdev.validation.invalid
PRIVATE_DEV_KEYCLOAK_ORIGIN=https://vgpdev.validation.invalid:8443
EOF

cat >"$fake_bin/hostname" <<'EOF'
#!/bin/bash
printf 'vgpdev\n'
EOF

cat >"$fake_bin/bash" <<'EOF'
#!/bin/bash
exec /usr/bin/bash "$@"
EOF

cat >"$fake_bin/node" <<'EOF'
#!/bin/bash
: >"$FAKE_NODE_INVOCATION_MARKER"
printf 'node: command not found\n' >&2
exit 127
EOF

cat >"$fake_bin/docker" <<'EOF'
#!/bin/bash
set -Eeuo pipefail

printf '%s\n' "$*" >>"$FAKE_DOCKER_COMMAND_LOG"

if [[ "$1" == info ]]; then
  exit 0
fi

if [[ "$1 ${2:-} ${3:-}" == "buildx imagetools inspect" ]]; then
  printf 'Name: ghcr.io/rubhern/videogame-platform:test\nDigest: %s\nPlatform: linux/amd64\n' "$FAKE_DIGEST"
  exit 0
fi

if [[ "$1 ${2:-}" == "image inspect" ]]; then
  format="$4"
  case "$format" in
    *RepoDigests*) printf '%s\n' "$FAKE_IMAGE" ;;
    *revision*) printf '%s\n' "$SOURCE_REVISION" ;;
    *version*) printf '0.15.0-SNAPSHOT\n' ;;
    *source*) printf 'https://github.com/rubhern/videogame-platform\n' ;;
    *Id*) printf 'sha256:candidate-image-id\n' ;;
    *) printf 'unexpected image inspection format: %s\n' "$format" >&2; exit 90 ;;
  esac
  exit 0
fi

if [[ "$1" == pull ]]; then
  exit 0
fi

if [[ "$1" == inspect ]]; then
  format="$3"
  case "$format" in
    *State.Status*) printf 'running\n' ;;
    *State.Health*) printf 'healthy\n' ;;
    *Image*) printf 'sha256:candidate-image-id\n' ;;
    *) printf 'unexpected container inspection format: %s\n' "$format" >&2; exit 91 ;;
  esac
  exit 0
fi

if [[ "$1" == compose ]]; then
  arguments=" $* "
  if [[ "$arguments" == *" config --format json "* ]]; then
    python3 - "$FAKE_REPOSITORY_ROOT" "$FAKE_SECRETS_DIRECTORY" "$FAKE_IMAGE" <<'PY'
import json
import pathlib
import sys

repository = pathlib.Path(sys.argv[1])
secrets_directory = pathlib.Path(sys.argv[2])
image = sys.argv[3]
secret_files = {
    name.replace("-", "_"): {"file": str(secrets_directory / name)}
    for name in (
        "postgres-admin-password",
        "application-db-password",
        "application-migration-db-password",
        "keycloak-db-password",
        "keycloak-admin-password",
        "keycloak-bff-client-secret",
        "igdb-client-id",
        "igdb-client-secret",
        "oidc-smoke-username",
        "oidc-smoke-password",
    )
}

def service(restart, secrets=(), environment=None, **extra):
    return {
        "restart": restart,
        "mem_limit": 134217728,
        "cpus": 0.25,
        "secrets": [{"source": name} for name in secrets],
        "environment": environment or {},
        **extra,
    }

services = {
    "postgres": service(
        "unless-stopped",
        (
            "postgres_admin_password",
            "application_db_password",
            "application_migration_db_password",
            "keycloak_db_password",
        ),
        image="postgres@sha256:" + "1" * 64,
    ),
    "keycloak": service(
        "unless-stopped",
        ("keycloak_db_password", "keycloak_admin_password", "keycloak_bff_client_secret"),
        build={"context": str(repository / "deploy/private-dev/keycloak")},
        ports=[{"host_ip": "127.0.0.1", "published": 8180, "target": 8080}],
        volumes=[{
            "source": str(repository / "docker/keycloak/import/videogame-platform-realm.json"),
            "target": "/opt/keycloak/data/import/videogame-platform-realm.json",
        }],
        read_only=True,
        cap_drop=["ALL"],
    ),
    "telemetry": service(
        "unless-stopped",
        image="otel/opentelemetry-collector@sha256:" + "2" * 64,
        read_only=True,
        cap_drop=["ALL"],
    ),
    "application": service(
        "unless-stopped",
        ("application_db_password", "keycloak_bff_client_secret", "igdb_client_id", "igdb_client_secret"),
        {
            "APPLICATION_FLYWAY_ENABLED": "false",
            "APPLICATION_SESSION_COOKIE_NAME": "__Host-vgp_session",
            "APPLICATION_SESSION_COOKIE_SECURE": "true",
            "TELEMETRY_DEPLOYMENT_ENVIRONMENT": "dev",
            "TELEMETRY_SERVICE_VERSION": "0.15.0-SNAPSHOT",
        },
        image=image,
        ports=[{"host_ip": "127.0.0.1", "published": 8080, "target": 8080}],
        read_only=True,
        cap_drop=["ALL"],
    ),
    "migration": service(
        "no",
        ("application_migration_db_password",),
        {
            "APPLICATION_MIGRATION_DB_PASSWORD_FILE": "/run/secrets/application_migration_db_password",
            "APPLICATION_MIGRATION_DB_URL": "jdbc:postgresql://postgres:5432/videogame_platform",
            "APPLICATION_MIGRATION_DB_USERNAME": "videogame_app_migrator",
        },
        image=image,
        networks={"data": None},
        read_only=True,
        cap_drop=["ALL"],
    ),
    "deployment-smoke": service(
        "no",
        ("oidc_smoke_username", "oidc_smoke_password"),
        {
            "EXPECTED_APPLICATION_VERSION": "0.15.0-SNAPSHOT",
            "EXPECTED_SOURCE_REVISION": "2" * 40,
            "PRIVATE_DEV_APPLICATION_ORIGIN": "https://vgpdev.validation.invalid",
            "PRIVATE_DEV_KEYCLOAK_ORIGIN": "https://vgpdev.validation.invalid:8443",
        },
        build={"context": str(repository / "deploy/private-dev/smoke")},
        shm_size=256 * 1024 * 1024,
        read_only=True,
        cap_drop=["ALL"],
    ),
}
json.dump(
    {
        "services": services,
        "networks": {"data": {"internal": True}, "telemetry": {"internal": True}},
        "secrets": secret_files,
    },
    sys.stdout,
)
PY
    exit 0
  fi
  if [[ "$arguments" == *" ps --quiet "* ]]; then
    service="${*: -1}"
    printf '%s-id\n' "$service"
    exit 0
  fi
  if [[ "$arguments" == *" build --pull deployment-smoke "* ]]; then
    exit 0
  fi
  if [[ "$arguments" == *" up --detach telemetry "* ||
        "$arguments" == *" run --rm telemetry-smoke "* ||
        "$arguments" == *" down --volumes --remove-orphans "* ]]; then
    exit 0
  fi
  if [[ "$arguments" == *" logs --no-color telemetry "* ]]; then
    printf '%s\n' \
      '{"otelcol.signal": "traces", "resource spans": 1, "spans": 1}' \
      '{"otelcol.signal": "metrics", "resource metrics": 1, "metrics": 1, "data points": 1}'
    exit 0
  fi
  if [[ "$arguments" == *" run --rm --no-deps migration "* ]]; then
    [[ "${FAKE_DEPLOY_MODE:-success}" != migration-failure ]] || exit 42
    printf 'VGP_MIGRATION_VERSION=20260913.120000\n'
    exit 0
  fi
  if [[ "$arguments" == *" up --detach --force-recreate --no-deps --wait --wait-timeout 180 application "* ]]; then
    [[ "${FAKE_DEPLOY_MODE:-success}" != activation-failure ]] || exit 43
    exit 0
  fi
  if [[ "$arguments" == *" run --rm --no-deps deployment-smoke "* ]]; then
    [[ "${FAKE_DEPLOY_MODE:-success}" != smoke-failure ]] || exit 44
    printf 'Private-dev deployment smoke passed.\n'
    exit 0
  fi
  if [[ "$arguments" == *" logs --no-color --no-log-prefix "*" application "* ]]; then
    printf '{"message":"HTTP request completed","correlationId":"%s","traceId":"%s","spanId":"0123456789abcdef"}\n' \
      "$SMOKE_CORRELATION_ID" "$SMOKE_TRACE_ID"
    exit 0
  fi
  if [[ "$arguments" == *" logs --no-color --no-log-prefix "*" telemetry "* ]]; then
    if [[ -e "$FAKE_TELEMETRY_STATE" ]]; then
      printf '{"otelcol.signal": "traces", "resource spans": 1, "spans": 1}\n'
    else
      : >"$FAKE_TELEMETRY_STATE"
    fi
    exit 0
  fi
fi

printf 'Unexpected fake docker command: %s\n' "$*" >&2
exit 92
EOF

chmod +x "$fake_bin/bash" "$fake_bin/docker" "$fake_bin/hostname" "$fake_bin/node"

run_deployment() {
  local mode="$1"
  local evidence_directory="$2"
  mkdir -p "$evidence_directory"
  PATH="$fake_bin:$PATH" \
  FAKE_DEPLOY_MODE="$mode" \
  FAKE_DIGEST="$digest" \
  FAKE_DOCKER_COMMAND_LOG="$command_log" \
  FAKE_IMAGE="$image" \
  FAKE_NODE_INVOCATION_MARKER="$node_invocation_marker" \
  FAKE_REPOSITORY_ROOT="$repository_root" \
  FAKE_SECRETS_DIRECTORY="$secrets_directory" \
  FAKE_TELEMETRY_STATE="$evidence_directory/telemetry-state" \
    "$deployment_command" \
      --env-file "$runtime_env" \
      --image "$image" \
      --source-revision "$source_revision" \
      --initiator issue-36-test \
      --evidence-directory "$evidence_directory"
}

assert_lock_released() {
  exec 7>"$lock_file"
  flock --nonblock 7 || {
    printf 'Deployment lock remained held after command exit.\n' >&2
    exit 1
  }
  flock --unlock 7
  exec 7>&-
}

assert_evidence() {
  local evidence_directory="$1"
  local expected_outcome="$2"
  local expected_phase="$3"
  python3 - "$evidence_directory" "$expected_outcome" "$expected_phase" "$source_revision" "$image" <<'PY'
import json
import pathlib
import sys

directory = pathlib.Path(sys.argv[1])
records = list(directory.glob("*.json"))
assert len(records) == 1, records
record = json.loads(records[0].read_text(encoding="utf-8"))
assert record["outcome"] == sys.argv[2], record
assert record["phase"] == sys.argv[3], record
assert record["sourceRevision"] == sys.argv[4]
assert record["image"] == sys.argv[5]
assert record["initiator"] == "issue-36-test"
PY
}

mutable_evidence="$temporary_directory/mutable-evidence"
mkdir -p "$mutable_evidence"
if PATH="$fake_bin:$PATH" "$deployment_command" \
    --env-file "$runtime_env" \
    --image ghcr.io/rubhern/videogame-platform:latest \
    --source-revision "$source_revision" \
    --initiator issue-36-test \
    --evidence-directory "$mutable_evidence" >/dev/null 2>&1; then
  printf 'Mutable image reference was accepted.\n' >&2
  exit 1
fi

: >"$command_log"
digest_evidence="$temporary_directory/digest-evidence"
mkdir -p "$digest_evidence"
if PATH="$fake_bin:$PATH" \
    FAKE_DEPLOY_MODE=success \
    FAKE_DIGEST=sha256:3333333333333333333333333333333333333333333333333333333333333333 \
    FAKE_DOCKER_COMMAND_LOG="$command_log" \
    FAKE_IMAGE="$image" \
    FAKE_NODE_INVOCATION_MARKER="$node_invocation_marker" \
    FAKE_REPOSITORY_ROOT="$repository_root" \
    FAKE_SECRETS_DIRECTORY="$secrets_directory" \
    "$deployment_command" \
      --env-file "$runtime_env" \
      --image "$image" \
      --source-revision "$source_revision" \
      --initiator issue-36-test \
      --evidence-directory "$digest_evidence" >/dev/null 2>&1; then
  printf 'Source-revision digest mismatch was reported as success.\n' >&2
  exit 1
fi
assert_evidence "$digest_evidence" failure immutable-image-verification
assert_lock_released
if grep -q '^pull ' "$command_log"; then
  printf 'Mismatched source-revision digest was pulled.\n' >&2
  exit 1
fi

: >"$command_log"
migration_evidence="$temporary_directory/migration-evidence"
if run_deployment migration-failure "$migration_evidence" >"$temporary_directory/migration-run.log" 2>&1; then
  printf 'Migration failure was reported as success.\n' >&2
  exit 1
fi
if [[ "${PRIVATE_DEV_TEST_DEBUG:-false}" == true ]]; then
  cat "$temporary_directory/migration-run.log"
fi
assert_evidence "$migration_evidence" failure database-migration
assert_lock_released
if grep -q 'wait-timeout 180 application' "$command_log"; then
  printf 'Application activation ran after a migration failure.\n' >&2
  exit 1
fi

: >"$command_log"
activation_evidence="$temporary_directory/activation-evidence"
if run_deployment activation-failure "$activation_evidence" >/dev/null 2>&1; then
  printf 'Readiness/activation failure was reported as success.\n' >&2
  exit 1
fi
assert_evidence "$activation_evidence" failure application-activation
assert_lock_released
if grep -q 'run --rm --no-deps deployment-smoke' "$command_log"; then
  printf 'Smoke ran after candidate activation failed.\n' >&2
  exit 1
fi

: >"$command_log"
smoke_evidence="$temporary_directory/smoke-evidence"
if run_deployment smoke-failure "$smoke_evidence" >/dev/null 2>&1; then
  printf 'Smoke failure was reported as success.\n' >&2
  exit 1
fi
assert_evidence "$smoke_evidence" failure deployment-smoke
assert_lock_released

: >"$command_log"
success_evidence="$temporary_directory/success-evidence"
run_deployment success "$success_evidence" >/dev/null
assert_evidence "$success_evidence" success complete
assert_lock_released
[[ ! -e "$node_invocation_marker" ]] || {
  printf 'Host deployment validation invoked Node.js.\n' >&2
  exit 1
}
python3 - "$success_evidence" <<'PY'
import json
import pathlib
import sys

record = json.loads(next(pathlib.Path(sys.argv[1]).glob("*.json")).read_text(encoding="utf-8"))
assert record["applicationVersion"] == "0.15.0-SNAPSHOT"
assert record["migrationVersion"] == "20260913.120000"
assert record["candidateContainerId"] == "application-id"
assert record["smokeChecks"] == [
    "liveness",
    "readiness",
    "version-metadata",
    "releases-api",
    "browser-shell",
    "oidc-bff-session",
    "diagnostic-metrics",
    "trace-correlation",
    "collector-trace-receipt",
]
PY

python3 - "$command_log" <<'PY'
import pathlib
import sys

commands = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
positions = {
    "verify": next(i for i, value in enumerate(commands) if "buildx imagetools inspect" in value),
    "migration": next(i for i, value in enumerate(commands) if "run --rm --no-deps migration" in value),
    "activation": next(i for i, value in enumerate(commands) if "wait-timeout 180 application" in value),
    "smoke": next(i for i, value in enumerate(commands) if "run --rm --no-deps deployment-smoke" in value),
}
assert positions["verify"] < positions["migration"] < positions["activation"] < positions["smoke"], positions
PY

: >"$command_log"
exec 8>"$lock_file"
flock --nonblock 8
if PATH="$fake_bin:$PATH" \
    "$deployment_command" \
      --env-file "$runtime_env" \
      --image "$image" \
      --source-revision "$source_revision" \
      --initiator issue-36-test \
      --evidence-directory "$temporary_directory" >/dev/null 2>&1; then
  printf 'Concurrent deployment lock was not enforced.\n' >&2
  exit 1
fi
[[ ! -s "$command_log" ]] || {
  printf 'Contended deployment performed Docker work.\n' >&2
  exit 1
}
flock --unlock 8
exec 8>&-
assert_lock_released

: >"$command_log"
rm -f -- "$node_invocation_marker"
PATH="$fake_bin:$PATH" \
FAKE_DIGEST="$digest" \
FAKE_DOCKER_COMMAND_LOG="$command_log" \
FAKE_IMAGE="$image" \
FAKE_NODE_INVOCATION_MARKER="$node_invocation_marker" \
FAKE_REPOSITORY_ROOT="$repository_root" \
FAKE_SECRETS_DIRECTORY="$secrets_directory" \
FAKE_TELEMETRY_STATE="$temporary_directory/telemetry-state" \
  "$runtime_validator" --telemetry-smoke >/dev/null
[[ ! -e "$node_invocation_marker" ]] || {
  printf 'Host telemetry smoke invoked Node.js.\n' >&2
  exit 1
}

printf 'Private-dev deployment orchestration validation passed.\n'
