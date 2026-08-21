#!/usr/bin/env bash
set -euo pipefail

rows="${1:-100000}"

case "$rows" in
  10000|100000|1000000) ;;
  *)
    echo "Usage: $0 [10000|100000|1000000]" >&2
    exit 2
    ;;
esac

./mvnw -pl backend \
  -Dtest=ReleaseBrowseScalabilityIT \
  -Drelease.scale.rows="$rows" \
  test
