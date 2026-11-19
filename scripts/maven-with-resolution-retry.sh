#!/usr/bin/env bash
set -euo pipefail

# Maven's HTTP retries do not cover a parent POM or build plugin reported as missing.
# Retry only resolution errors before compilation or analysis begins.
log="$(mktemp)"
trap 'rm -f -- "$log"' EXIT

central_missing='Could not find artifact .* in central \(https://repo\.maven\.apache\.org/maven2\)'
is_resolution_failure() {
  grep -Eq "Non-resolvable parent POM.*$central_missing" "$log" ||
    { grep -Eq 'Plugin .* or one of its dependencies could not be resolved:' "$log" &&
      grep -Eq "$central_missing" "$log"; }
}

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
  if (( delay == 0 )) || ! is_resolution_failure; then
    exit "${result[0]}"
  fi

  printf 'Maven Central reported a missing build artifact. Retrying in %s seconds.\n' "$delay" >&2
  sleep "$delay"
  retry_options=(--update-snapshots)
done
