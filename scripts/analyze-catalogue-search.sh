#!/usr/bin/env bash
set -euo pipefail

games="${1:-100000}"

case "$games" in
  10000|100000|1000000) ;;
  *)
    echo "Usage: $0 [10000|100000|1000000]" >&2
    exit 2
    ;;
esac

./mvnw -pl backend \
  -Dtest=CatalogueSearchScalabilityIT \
  -Dsearch.scale.games="$games" \
  test
