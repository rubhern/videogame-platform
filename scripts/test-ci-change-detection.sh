#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
detector="$repository_root/scripts/detect-ci-changes.sh"
result_verifier="$repository_root/scripts/verify-ci-results.sh"

failures=0

run_case() {
  local name="$1"
  local paths="$2"
  local enabled_csv="$3"
  local disabled_csv="$4"
  local extra_argument="${5:-}"
  local output

  if [[ -n "$extra_argument" ]]; then
    output="$(printf '%s\n' "$paths" | "$detector" --paths-from-stdin "$extra_argument")"
  else
    output="$(printf '%s\n' "$paths" | "$detector" --paths-from-stdin)"
  fi

  local category
  IFS=',' read -r -a enabled <<<"$enabled_csv"
  for category in "${enabled[@]}"; do
    if ! grep -qx "${category}=true" <<<"$output"; then
      printf 'FAIL %-24s expected %s=true\n' "$name" "$category" >&2
      failures=$((failures + 1))
    fi
  done

  IFS=',' read -r -a disabled <<<"$disabled_csv"
  for category in "${disabled[@]}"; do
    if ! grep -qx "${category}=false" <<<"$output"; then
      printf 'FAIL %-24s expected %s=false\n' "$name" "$category" >&2
      failures=$((failures + 1))
    fi
  done

  printf 'PASS %-24s\n' "$name"
}

run_case docs-only \
  'docs/product/product-brief.md' \
  'documentation' \
  'openapi,frontend,browser,backend,migrations,identity,provider_fixtures,container,build,ci,dependencies,npm_dependencies,sonar,codeql_java,codeql_javascript'

run_case openapi \
  'docs/architecture/api/openapi.yaml' \
  'documentation,openapi,frontend,backend' \
  'browser,migrations,identity,provider_fixtures,container,ci,sonar'

run_case frontend \
  'frontend/src/features/releases/releases-shell.tsx' \
  'frontend,browser,codeql_javascript' \
  'backend,migrations,identity,provider_fixtures,container,sonar'

run_case frontend-unit-test \
  'frontend/src/features/releases/releases-shell.test.tsx' \
  'frontend,codeql_javascript' \
  'browser,backend,migrations,identity,provider_fixtures,container,sonar'

run_case backend \
  'backend/src/main/java/com/videogameplatform/catalogue/application/internal/ReleaseCatalogueService.java' \
  'backend,sonar,codeql_java' \
  'frontend,browser,migrations,identity,provider_fixtures,container'

run_case migration \
  'backend/src/main/resources/db/migration/V99999999_000000__test.sql' \
  'backend,migrations' \
  'frontend,browser,identity,provider_fixtures,container'

run_case agent-configuration \
  $'CLAUDE.md\nAGENTS.md\n.claude/settings.json\n.claude/rules/hexagonal-boundaries.md\n.claude/skills/validate\n.worktreeinclude' \
  'documentation' \
  'openapi,frontend,browser,backend,migrations,identity,provider_fixtures,container,build,ci,dependencies,npm_dependencies,sonar,codeql_java,codeql_javascript'

run_case agent-skills \
  $'.agents/skills/validate/SKILL.md\n.codex/config.toml' \
  'documentation' \
  'openapi,frontend,browser,backend,migrations,identity,provider_fixtures,container,build,ci,dependencies,npm_dependencies,sonar,codeql_java,codeql_javascript'

run_case topology-budget \
  'scripts/validate-topology-budget.sh' \
  'documentation,container,build' \
  'openapi,frontend,browser,backend,migrations,identity,provider_fixtures,ci,dependencies,npm_dependencies,sonar,codeql_java,codeql_javascript'

run_case identity \
  'backend/src/main/java/com/videogameplatform/identity/configuration/IdentitySecurityConfiguration.java' \
  'backend,browser,identity,sonar,codeql_java' \
  'frontend,migrations,provider_fixtures,container'

run_case docker \
  'Dockerfile' \
  'container,build,frontend,backend' \
  'browser,migrations,identity,provider_fixtures,sonar'

run_case igdb \
  'tools/igdb-poc/src/test/resources/fixtures/example.json' \
  'provider_fixtures' \
  'frontend,browser,backend,migrations,identity,container,sonar,codeql_java'

run_case igdb-java \
  'tools/igdb-poc/src/main/java/com/videogameplatform/tools/igdb/IgdbPocApplication.java' \
  'provider_fixtures,codeql_java' \
  'frontend,browser,backend,migrations,identity,container,sonar'

run_case workflow \
  '.github/workflows/build-and-verify.yml' \
  'documentation,openapi,frontend,browser,backend,migrations,identity,provider_fixtures,container,build,ci,dependencies,npm_dependencies,sonar,codeql_java,codeql_javascript' \
  ''

run_case frontend-backend \
  $'frontend/src/pages/releases-page.tsx\nbackend/src/main/java/com/videogameplatform/catalogue/application/internal/ReleaseCatalogueService.java' \
  'frontend,browser,backend,sonar,codeql_java,codeql_javascript' \
  'migrations,identity,provider_fixtures,container'

run_case root-build \
  'pom.xml' \
  'documentation,build,dependencies,backend,migrations,identity,browser,container,sonar,codeql_java' \
  'frontend,provider_fixtures,npm_dependencies,codeql_javascript'

run_case npm-lock \
  'package-lock.json' \
  'documentation,openapi,frontend,dependencies,npm_dependencies,codeql_javascript' \
  'browser,backend,migrations,identity,provider_fixtures,container,sonar,codeql_java'

run_case push-main-full \
  'docs/product/product-brief.md' \
  'documentation,openapi,frontend,browser,backend,migrations,identity,provider_fixtures,container,build,ci,dependencies,npm_dependencies,sonar,codeql_java,codeql_javascript' \
  '' \
  '--full'

run_case unknown-fail-safe \
  'new-top-level-runtime.conf' \
  'documentation,openapi,frontend,browser,backend,migrations,identity,provider_fixtures,container,build,ci,dependencies,npm_dependencies,sonar,codeql_java,codeql_javascript' \
  ''

range_repository="$(mktemp -d)"
cleanup() {
  rm -r -- "$range_repository"
}
trap cleanup EXIT

git -C "$range_repository" init --quiet
git -C "$range_repository" config user.name 'CI classifier test'
git -C "$range_repository" config user.email 'ci-classifier@localhost.invalid'
mkdir -p \
  "$range_repository/docs/product" \
  "$range_repository/backend/src/main/java/example" \
  "$range_repository/frontend/src/features"
printf 'base\n' >"$range_repository/docs/product/changed.md"
printf 'base\n' >"$range_repository/backend/src/main/java/example/Deleted.java"
printf 'base\n' >"$range_repository/frontend/src/features/renamed.tsx"
git -C "$range_repository" add .
git -C "$range_repository" commit --quiet -m base
range_base="$(git -C "$range_repository" rev-parse HEAD)"

printf 'modified\n' >>"$range_repository/docs/product/changed.md"
rm -- "$range_repository/backend/src/main/java/example/Deleted.java"
mkdir -p "$range_repository/tools/igdb-poc/src/test/resources/fixtures"
mv \
  "$range_repository/frontend/src/features/renamed.tsx" \
  "$range_repository/tools/igdb-poc/src/test/resources/fixtures/renamed.json"
printf 'FROM scratch\n' >"$range_repository/Dockerfile"
git -C "$range_repository" add -A
git -C "$range_repository" commit --quiet -m head
range_head="$(git -C "$range_repository" rev-parse HEAD)"

range_output="$(
  cd "$range_repository"
  "$detector" --base "$range_base" --head "$range_head"
)"
for category in documentation frontend backend provider_fixtures container build; do
  if ! grep -qx "${category}=true" <<<"$range_output"; then
    printf 'FAIL git-range-paths          expected %s=true\n' "$category" >&2
    failures=$((failures + 1))
  fi
done
printf 'PASS git-range-paths          added/modified/deleted/renamed\n'

"$result_verifier" \
  documentation true success \
  backend false skipped >/dev/null

if "$result_verifier" backend true skipped >/dev/null 2>&1; then
  printf 'FAIL required-result-gate     accepted a skipped required job\n' >&2
  failures=$((failures + 1))
else
  printf 'PASS required-result-gate\n'
fi

if "$result_verifier" backend false failure >/dev/null 2>&1; then
  printf 'FAIL inapplicable-result-gate accepted a failed job\n' >&2
  failures=$((failures + 1))
else
  printf 'PASS inapplicable-result-gate\n'
fi

for unexpected_result in failure cancelled; do
  if "$result_verifier" backend true "$unexpected_result" >/dev/null 2>&1; then
    printf 'FAIL required-%-16s accepted result=%s\n' "$unexpected_result" "$unexpected_result" >&2
    failures=$((failures + 1))
  else
    printf 'PASS required-%s-result-gate\n' "$unexpected_result"
  fi
done

if "$result_verifier" backend false success >/dev/null 2>&1; then
  printf 'FAIL unexpected-execution-gate accepted a successful inapplicable job\n' >&2
  failures=$((failures + 1))
else
  printf 'PASS unexpected-execution-gate\n'
fi

if ((failures > 0)); then
  printf 'CI change-detection tests failed with %d problem(s).\n' "$failures" >&2
  exit 1
fi

printf 'CI change-detection tests passed.\n'
