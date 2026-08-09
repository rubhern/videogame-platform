#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

if ! docker info >/dev/null 2>&1; then
  echo "Docker must be running to validate PostgreSQL 18 migrations with Testcontainers." >&2
  exit 1
fi

./mvnw -pl backend -Dtest=CataloguePersistenceIntegrationTest test
