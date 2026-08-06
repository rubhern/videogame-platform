#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  printf 'OpenAPI validation requires Node.js 24 and npm.\n' >&2
  exit 1
fi

if [[ "$(node -p 'process.versions.node.split(".")[0]')" != "24" ]]; then
  printf 'OpenAPI validation requires the approved Node.js 24 line. Found: %s\n' \
    "$(node --version)" >&2
  exit 1
fi

if [[ ! -x "node_modules/.bin/redocly" ]]; then
  printf 'OpenAPI validation dependencies are missing. Run: npm ci\n' >&2
  exit 1
fi

npm run --silent validate:openapi
printf 'OpenAPI contract validation passed.\n'
