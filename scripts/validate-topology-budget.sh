#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repository_root/scripts/backend-artifact.sh"
compose_file="$repository_root/compose.yaml"
example_env_file="$repository_root/.env.example"

max_cpus="2"
max_memory_bytes="$((12 * 1024 * 1024 * 1024))"
temporary_directory="$(mktemp -d)"
topology_file="$temporary_directory/topology.json"

cleanup() {
  rm -r -- "$temporary_directory"
}

trap cleanup EXIT

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  printf 'Docker Compose is required to validate the complete topology budget.\n' >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  printf 'Python 3 is required to validate the rendered Compose resource limits.\n' >&2
  exit 1
fi

if ! application_version="$(backend_reactor_version)"; then
  printf 'Could not read the reactor version from pom.xml.\n' >&2
  exit 1
fi

APPLICATION_VERSION="$application_version" docker compose \
  --env-file "$example_env_file" \
  --file "$compose_file" \
  --profile full \
  config \
  --format json >"$topology_file"

python3 - "$max_cpus" "$max_memory_bytes" "$topology_file" <<'PY'
import json
from decimal import Decimal
from pathlib import Path
import sys

max_cpus = Decimal(sys.argv[1])
max_memory_bytes = int(sys.argv[2])
topology_file = Path(sys.argv[3])
required_services = {"application", "keycloak", "postgres"}

topology = json.loads(topology_file.read_text(encoding="utf-8"))
services = topology.get("services", {})
missing_services = sorted(required_services - services.keys())
if missing_services:
    raise SystemExit(
        "complete topology is missing required services: " + ", ".join(missing_services)
    )

rows = []
for name, service in sorted(services.items()):
    cpu_value = service.get("cpus")
    memory_value = service.get("mem_limit")
    if cpu_value is None or memory_value is None:
        raise SystemExit(f"{name} must define both cpus and mem_limit")

    cpus = Decimal(str(cpu_value))
    memory_bytes = int(memory_value)
    if cpus <= 0 or memory_bytes <= 0:
        raise SystemExit(f"{name} resource limits must be positive")
    rows.append((name, cpus, memory_bytes))

total_cpus = sum((row[1] for row in rows), Decimal("0"))
total_memory_bytes = sum(row[2] for row in rows)

if total_cpus > max_cpus:
    raise SystemExit(
        f"configured topology CPU limit {total_cpus} exceeds {max_cpus} OCPU"
    )
if total_memory_bytes > max_memory_bytes:
    raise SystemExit(
        "configured topology memory limit "
        f"{total_memory_bytes} exceeds {max_memory_bytes} bytes"
    )

print("SERVICE       CPU LIMIT   MEMORY LIMIT")
for name, cpus, memory_bytes in rows:
    print(f"{name:<13} {cpus:>9.2f}   {memory_bytes / (1024 ** 2):>8.0f} MiB")
print(f"{'TOTAL':<13} {total_cpus:>9.2f}   {total_memory_bytes / (1024 ** 3):>8.2f} GiB")
print(f"{'OCI LIMIT':<13} {max_cpus:>9.2f}   {max_memory_bytes / (1024 ** 3):>8.2f} GiB")
print(
    f"{'HEADROOM':<13} {max_cpus - total_cpus:>9.2f}   "
    f"{(max_memory_bytes - total_memory_bytes) / (1024 ** 3):>8.2f} GiB"
)
print("Complete topology resource-budget validation passed.")
PY
