#!/usr/bin/env bash
set -euo pipefail

# Maven's HTTP retries do not retry a parent POM reported as missing.
# Retry only this failure during initial project scanning.
log="$(mktemp)"
trap 'rm -f -- "$log"' EXIT

retry_options=()
for delay in 5 15 30 0; do
  if ./mvnw "${retry_options[@]}" "$@" 2>&1 | tee "$log"; then
    exit 0
  else
    result=("${PIPESTATUS[@]}")
  fi

  if (( result[1] != 0 )); then
    exit "${result[1]}"
  fi
  if (( delay == 0 )) || ! grep -Eq 'Non-resolvable parent POM.*Could not find artifact .* in central \(https://repo\.maven\.apache\.org/maven2\)' "$log"; then
    exit "${result[0]}"
  fi

  printf 'Maven Central reported a missing parent POM. Retrying in %s seconds.\n' "$delay" >&2
  sleep "$delay"
  retry_options=(--update-snapshots)
done
