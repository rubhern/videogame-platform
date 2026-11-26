#!/usr/bin/env bash
set -euo pipefail

# Retry parent POM and build plugin resolution when Maven Central reports a missing
# artifact or still returns HTTP 429 after Maven's transport retries.
log="$(mktemp)"
trap 'rm -f -- "$log"' EXIT

central_url='\(https://repo\.maven\.apache\.org/maven2\)'
central_missing="Could not find artifact .* in central $central_url"
central_throttled="Could not transfer artifact .* from/to central $central_url: (HTTP Status|status code): 429([^0-9]|$)"
central_resolution_error="($central_missing|$central_throttled)"

is_resolution_failure() {
  grep -Eq "Non-resolvable parent POM.*$central_resolution_error" "$log" ||
    { grep -Eq 'Plugin .* or one of its dependencies could not be resolved:' "$log" &&
      grep -Eq "$central_resolution_error" "$log"; }
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

  printf 'Maven Central could not resolve a build artifact. Retrying in %s seconds.\n' "$delay" >&2
  sleep "$delay"
  # Limit per-request retries on replays so they fit within the CI job budget.
  retry_options=(--update-snapshots -Daether.transport.http.retryHandler.count=1)
done
