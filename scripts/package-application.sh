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
# The caller may omit duplicate tests only when CI requires the separate backend gate.
# Local packaging and CI journeys without that gate retain their test coverage.
maven_test_arguments=()
case "${PACKAGE_SKIP_BACKEND_TESTS:-false}" in
  true) maven_test_arguments+=(-DskipTests) ;;
  false) ;;
  *) printf 'PACKAGE_SKIP_BACKEND_TESTS must be true or false.\n' >&2; exit 2 ;;
esac
./mvnw --batch-mode --no-transfer-progress -Pwith-frontend \
  "${maven_test_arguments[@]}" -Dsource.revision="$source_revision" clean package

application_jar="$(resolve_backend_jar)"
printf 'Combined application package: %s\n' \
  "$application_jar"
