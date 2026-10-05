#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repository_root/scripts/backend-artifact.sh"

cd "$repository_root"

npm ci
npm run frontend:generate-api
source_revision="${SOURCE_REVISION:-local-development}"
APPLICATION_VERSION="$(backend_reactor_version)" \
SOURCE_REVISION="$source_revision" npm run frontend:build
./mvnw -Pwith-frontend -Dsource.revision="$source_revision" clean package

application_jar="$(resolve_backend_jar)"
printf 'Combined application package: %s\n' \
  "$application_jar"
