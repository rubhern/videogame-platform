#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage:
  detect-ci-changes.sh --base <sha> --head <sha> [--full]
  detect-ci-changes.sh --paths-from-stdin [--full]

Writes GitHub-compatible key=value outputs to stdout. Diagnostics go to stderr.
EOF
}

base_sha=""
head_sha=""
full_validation=false
paths_from_stdin=false

while (($# > 0)); do
  case "$1" in
    --base)
      base_sha="${2:-}"
      shift 2
      ;;
    --head)
      head_sha="${2:-}"
      shift 2
      ;;
    --full)
      full_validation=true
      shift
      ;;
    --paths-from-stdin)
      paths_from_stdin=true
      shift
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

categories=(
  documentation
  openapi
  frontend
  browser
  backend
  migrations
  identity
  provider_fixtures
  container
  build
  ci
  dependencies
  npm_dependencies
  sonar
  codeql_java
  codeql_javascript
)

declare -A selected=()
for category in "${categories[@]}"; do
  selected["$category"]=false
done

enable() {
  local category
  for category in "$@"; do
    selected["$category"]=true
  done
}

enable_all() {
  local category
  for category in "${categories[@]}"; do
    selected["$category"]=true
  done
}

declare -a changed_paths=()
if [[ "$paths_from_stdin" == "true" ]]; then
  mapfile -t changed_paths
else
  if [[ -z "$base_sha" || -z "$head_sha" ]]; then
    printf '%s\n' '--base and --head are required unless --paths-from-stdin is used.' >&2
    usage >&2
    exit 2
  fi
  git rev-parse --verify "${head_sha}^{commit}" >/dev/null
  if [[ "$base_sha" == "0000000000000000000000000000000000000000" ]]; then
    while IFS= read -r -d '' path; do
      changed_paths+=("$path")
    done < <(git diff-tree --root --no-commit-id --recursive --name-only -z --no-renames "$head_sha")
  else
    git rev-parse --verify "${base_sha}^{commit}" >/dev/null
    while IFS= read -r -d '' path; do
      changed_paths+=("$path")
    done < <(git diff --name-only -z --no-renames "$base_sha" "$head_sha")
  fi
fi

unknown_path=false
for path in "${changed_paths[@]}"; do
  [[ -n "$path" ]] || continue
  matched=false

  case "$path" in
    docs/architecture/api/openapi.yaml | docs/architecture/api/reference/* | redocly.yaml | tools/openapi-validation/* | scripts/build-openapi-docs.sh | scripts/validate-openapi.sh)
      enable documentation openapi frontend backend
      matched=true
      ;;
    .github/workflows/* | .github/dependabot.yml | scripts/detect-ci-changes.sh | scripts/test-ci-change-detection.sh | scripts/verify-ci-results.sh | scripts/validate-actions.sh)
      enable ci documentation
      matched=true
      ;;
    *.md | docs/* | .agents/* | .codex/* | .github/ISSUE_TEMPLATE/* | .github/pull_request_template.md | .gitignore | .gitattributes)
      enable documentation
      matched=true
      ;;
    Dockerfile | .dockerignore | scripts/validate-container-image.sh)
      enable container build frontend backend
      matched=true
      ;;
    scripts/package-application.sh | scripts/validate-browser.sh)
      enable build frontend browser backend container
      matched=true
      ;;
    scripts/validate-identity.sh | docker/keycloak/* | backend/src/main/resources/application-oidc.yaml)
      enable identity browser backend
      matched=true
      ;;
    scripts/validate-migrations.sh | docker/postgres/* | backend/src/main/resources/db/*)
      enable migrations backend
      matched=true
      ;;
    compose.yaml)
      enable build container identity migrations backend
      matched=true
      ;;
    pom.xml | mvnw | mvnw.cmd | .mvn/*)
      enable documentation build dependencies backend migrations identity browser container sonar codeql_java
      matched=true
      ;;
    backend/pom.xml)
      enable documentation build dependencies backend sonar codeql_java
      matched=true
      ;;
    package.json)
      enable build dependencies npm_dependencies documentation openapi frontend browser codeql_javascript
      matched=true
      ;;
    package-lock.json)
      enable dependencies npm_dependencies documentation openapi frontend codeql_javascript
      matched=true
      ;;
    frontend/package.json | frontend/eslint.config.js | frontend/index.html | frontend/playwright.config.ts | frontend/tsconfig*.json | frontend/vite.config.ts)
      enable build dependencies npm_dependencies frontend browser codeql_javascript
      matched=true
      ;;
    frontend/src/*.test.ts | frontend/src/*.test.tsx | frontend/src/test/*)
      enable frontend codeql_javascript
      matched=true
      ;;
    frontend/src/* | frontend/public/* | frontend/tests/*)
      enable frontend browser codeql_javascript
      matched=true
      ;;
    backend/src/main/java/com/videogameplatform/identity/* | backend/src/test/java/com/videogameplatform/identity/* | backend/src/test/java/com/videogameplatform/api/delivery/SessionSecurityIntegrationTest.java | backend/src/main/java/com/videogameplatform/api/delivery/SessionController.java)
      enable backend identity browser sonar codeql_java
      matched=true
      ;;
    backend/src/main/java/*/adapter/persistence/* | backend/src/test/java/*/adapter/persistence/* | backend/src/test/java/com/videogameplatform/test/PostgreSqlTestDatabase.java)
      enable backend migrations sonar codeql_java
      matched=true
      ;;
    backend/src/main/* | backend/src/test/*)
      enable backend sonar codeql_java
      matched=true
      ;;
    tools/igdb-poc/pom.xml)
      enable provider_fixtures dependencies codeql_java
      matched=true
      ;;
    tools/igdb-poc/src/main/java/* | tools/igdb-poc/src/test/java/*)
      enable provider_fixtures codeql_java
      matched=true
      ;;
    tools/igdb-poc/*)
      enable provider_fixtures
      matched=true
      ;;
    scripts/validate-docs.sh | scripts/validate-prerequisites.sh | .env.example | backend/.env.example)
      enable documentation
      matched=true
      ;;
    scripts/*)
      enable ci build
      matched=true
      ;;
  esac

  if [[ "$matched" == "false" ]]; then
    printf 'Unclassified path activates all CI categories: %s\n' "$path" >&2
    unknown_path=true
  fi
done

if [[ "$unknown_path" == "true" || "${selected[ci]}" == "true" || "$full_validation" == "true" ]]; then
  enable_all
fi

for category in "${categories[@]}"; do
  printf '%s=%s\n' "$category" "${selected[$category]}"
done
printf 'changed_files_count=%d\n' "${#changed_paths[@]}"

printf 'CI change classification (%d path(s), full=%s):' \
  "${#changed_paths[@]}" "$full_validation" >&2
for category in "${categories[@]}"; do
  if [[ "${selected[$category]}" == "true" ]]; then
    printf ' %s' "$category" >&2
  fi
done
printf '\n' >&2
