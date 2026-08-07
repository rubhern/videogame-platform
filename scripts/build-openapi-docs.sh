#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_FILE="docs/architecture/api/reference/index.html"
cd "$ROOT_DIR"

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  printf 'OpenAPI documentation generation requires Node.js 24 and npm.\n' >&2
  exit 1
fi

if [[ "$(node -p 'process.versions.node.split(".")[0]')" != "24" ]]; then
  printf 'OpenAPI documentation generation requires the approved Node.js 24 line. Found: %s\n' \
    "$(node --version)" >&2
  exit 1
fi

if [[ ! -x "node_modules/.bin/redocly" ]]; then
  printf 'OpenAPI documentation dependencies are missing. Run: npm ci\n' >&2
  exit 1
fi

bash scripts/validate-openapi.sh
mkdir -p "$(dirname "$OUTPUT_FILE")"
npm run --silent build:openapi-docs

if [[ ! -s "$OUTPUT_FILE" ]]; then
  printf 'Generated OpenAPI documentation is missing or empty: %s\n' "$OUTPUT_FILE" >&2
  exit 1
fi

printf 'OpenAPI web documentation generated at %s.\n' "$OUTPUT_FILE"
