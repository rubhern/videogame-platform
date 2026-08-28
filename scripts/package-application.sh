#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repository_root/scripts/backend-artifact.sh"

cd "$repository_root"

npm ci
npm run frontend:generate-api
npm run frontend:build
./mvnw -Pwith-frontend clean package

application_jar="$(resolve_backend_jar)"
printf 'Combined application package: %s\n' \
  "$application_jar"
