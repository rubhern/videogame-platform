#!/usr/bin/env bash
# Fast boundary regression checks; a fake Docker CLI never creates/removes real resources.
set -Eeuo pipefail
tool_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
export FAKE_CALLS="$test_dir/calls" FAKE_SQL="$test_dir/sql"
cat > "$test_dir/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_CALLS"
case "$1" in
  context) printf '%s\n' "${FAKE_ENDPOINT:-unix:///var/run/docker.sock}" ;;
  info|container) ;;
  inspect)
    if [[ "$*" == *'{{range .Mounts}}{{.Name}}{{end}}'* ]]; then
      printf '%s\n' "$(printf '%064d' 1)"
    elif [[ "$*" == *'.Mounts'* ]]; then
      printf '%s\n' "$FAKE_MOUNTS"
    else
      printf '%s\n' "$FAKE_IDENTITY"
    fi ;;
  ps) printf '%s\n' "${FAKE_SOURCE:-}" ;;
  exec) cat > "$FAKE_SQL" ;;
  volume) ;;
  rm) ;;
  *) printf 'Unexpected fake Docker operation\n' >&2; exit 1 ;;
esac
SH
chmod +x "$test_dir/docker"
export PATH="$test_dir:$PATH"
unset DOCKER_CONTEXT DOCKER_HOST
export FAKE_IDENTITY='disposable|postgres:18.4-bookworm|true'
export FAKE_MOUNTS="volume:$(printf '%064d' 1):/var/lib/postgresql"
checks=0

reject() {
  : > "$FAKE_CALLS"
  if bash "$tool_dir/dataset.sh" "$@" > "$test_dir/output" 2>&1; then
    printf 'Expected rejection: %s\n' "$*" >&2; exit 1
  fi
  while IFS= read -r call; do
    case "$call" in
      exec\ *|rm\ *|run\ *|volume\ rm\ *) printf 'Rejected input reached a database/destructive operation: %s\n' "$call" >&2; exit 1 ;;
    esac
  done < "$FAKE_CALLS"
  checks=$((checks+1))
}
reject generate current
reject generate growth 2026-02-30
reject generate large 2026-10-07 videogame_platform
export DOCKER_HOST=tcp://private-dev:2375
reject generate growth
unset DOCKER_HOST
export DOCKER_HOST=unix:///var/run/docker.sock DOCKER_CONTEXT=private-dev FAKE_ENDPOINT=ssh://private-dev
reject generate growth
unset DOCKER_HOST DOCKER_CONTEXT FAKE_ENDPOINT
export FAKE_IDENTITY='private-dev|postgres:18.4-bookworm|true'
reject generate growth
reject stop
export FAKE_IDENTITY='disposable|postgres:18.4-bookworm|false'
reject stop
export FAKE_IDENTITY='disposable|postgres:18.4-bookworm|true'
export FAKE_MOUNTS='volume:videogame-platform_postgres-data:/var/lib/postgresql'
reject generate large
reject stop
export FAKE_MOUNTS='bind::/var/lib/postgresql'
reject stop
export FAKE_MOUNTS="volume:$(printf '%064d' 1):/var/lib/postgresql"
reject current
export FAKE_SOURCE=local-postgres
: > "$FAKE_CALLS"
bash "$tool_dir/dataset.sh" current > "$test_dir/output"
[[ "$(<"$FAKE_SQL")" == *'REPEATABLE READ READ ONLY'* ]]
[[ "$(<"$FAKE_CALLS")" == *'-U videogame_app -d videogame_platform -v profile=current'* ]]
checks=$((checks+1))
: > "$FAKE_CALLS"
bash "$tool_dir/dataset.sh" stop > "$test_dir/output" 2>&1
[[ "$(<"$FAKE_CALLS")" == *'rm --force videogame-platform-performance-postgres'* ]]
[[ "$(<"$FAKE_CALLS")" == *"volume rm $(printf '%064d' 1)"* ]]
checks=$((checks+1))
printf 'Performance boundary checks passed (%s cases).\n' "$checks"
