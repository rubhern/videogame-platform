#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

required_files=(
  "AGENTS.md"
  "README.md"
  "docs/product/product-brief.md"
  "docs/product/clickable-prototype.md"
  "docs/product/mvp-story-map.md"
  "docs/product/assumptions.md"
  "docs/product/open-questions.md"
  "docs/product/glossary.md"
  "docs/research/prototype-usability-test-guide.md"
  "docs/research/simulated-session-observation-sheets.md"
  "docs/research/simulated-round-synthesis.md"
  "docs/reference/video-game-platform-vision.pdf"
  "docs/development/codex-setup.md"
  "docs/development/openapi-validation.md"
  "docs/development/openapi-web-documentation.md"
  "docs/development/delivery-lifecycle.md"
  "docs/architecture/domain/mvp-domain-model.md"
  "docs/architecture/application/mvp-use-cases.md"
  "docs/architecture/mvp-solution-architecture.md"
  "docs/architecture/api/api-conventions.md"
  "docs/architecture/api/openapi.yaml"
  "docs/architecture/api/reference/index.html"
  "docs/architecture/deployment/mvp-platform-and-delivery.md"
  "docs/architecture/technology/mvp-technology-baseline.md"
  "docs/decisions/0001-reference-igdb-cover-images.md"
  "docs/decisions/0002-use-a-modular-monolith-and-relational-data-boundary.md"
  "docs/decisions/0003-use-a-same-origin-bff-and-http-json-api.md"
  "docs/decisions/0004-synchronize-and-serve-local-catalogue-data.md"
  "docs/decisions/0005-host-private-dev-on-oci-always-free.md"
  "docs/decisions/0006-use-postgresql-and-versioned-forward-migrations.md"
  "docs/decisions/0007-use-keycloak-as-the-initial-identity-provider.md"
  "docs/decisions/0008-use-github-actions-and-ghcr-for-initial-delivery.md"
  "docs/decisions/0009-use-opentelemetry-compatible-instrumentation.md"
  "docs/decisions/0010-use-java-25-spring-boot-4-and-spring-modulith.md"
  "docs/decisions/0011-use-postgresql-and-flyway-for-application-persistence.md"
  "docs/decisions/0012-use-react-typescript-and-vite-for-the-web-frontend.md"
  "package.json"
  "package-lock.json"
  "redocly.yaml"
  "scripts/validate-openapi.sh"
  "scripts/build-openapi-docs.sh"
  "tools/openapi-validation/syntax.redocly.yaml"
  "tools/openapi-validation/schemas.redocly.yaml"
  "tools/openapi-validation/examples.redocly.yaml"
  "tools/openapi-validation/normalize-generated-html.mjs"
  ".agents/skills/product-brief-review/SKILL.md"
)

for file in "${required_files[@]}"; do
  if [[ ! -f "$file" ]]; then
    printf 'Missing required file: %s\n' "$file" >&2
    exit 1
  fi
done

while IFS= read -r zone_identifier; do
  printf 'Windows Zone.Identifier metadata must not be committed: %s\n' \
    "$zone_identifier" >&2
  exit 1
done < <(find . -type f -name '*:Zone.Identifier' -print)

python3 - "$ROOT_DIR" <<'PY'
import pathlib
import re
import sys
from urllib.parse import unquote

root = pathlib.Path(sys.argv[1])
link_pattern = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
errors = []

expected_statuses = {
    "docs/product/product-brief.md": "Approved",
    "docs/architecture/domain/mvp-domain-model.md": "Approved",
    "docs/architecture/application/mvp-use-cases.md": "Approved",
    "docs/architecture/mvp-solution-architecture.md": "Approved",
    "docs/architecture/api/api-conventions.md": "Approved",
    "docs/architecture/deployment/mvp-platform-and-delivery.md": "Approved",
    "docs/architecture/technology/mvp-technology-baseline.md": "Approved",
    "docs/development/delivery-lifecycle.md": "Approved",
    "docs/decisions/0001-reference-igdb-cover-images.md": "Accepted",
    "docs/decisions/0002-use-a-modular-monolith-and-relational-data-boundary.md": "Accepted",
    "docs/decisions/0003-use-a-same-origin-bff-and-http-json-api.md": "Accepted",
    "docs/decisions/0004-synchronize-and-serve-local-catalogue-data.md": "Accepted",
    "docs/decisions/0005-host-private-dev-on-oci-always-free.md": "Accepted",
    "docs/decisions/0006-use-postgresql-and-versioned-forward-migrations.md": "Accepted",
    "docs/decisions/0007-use-keycloak-as-the-initial-identity-provider.md": "Accepted",
    "docs/decisions/0008-use-github-actions-and-ghcr-for-initial-delivery.md": "Accepted",
    "docs/decisions/0009-use-opentelemetry-compatible-instrumentation.md": "Accepted",
    "docs/decisions/0010-use-java-25-spring-boot-4-and-spring-modulith.md": "Accepted",
    "docs/decisions/0011-use-postgresql-and-flyway-for-application-persistence.md": "Accepted",
    "docs/decisions/0012-use-react-typescript-and-vite-for-the-web-frontend.md": "Accepted",
}

for relative, status in expected_statuses.items():
    document = root / relative
    marker = f"- **Status:** {status}"
    if marker not in document.read_text(encoding="utf-8").splitlines()[:20]:
        errors.append(f"{relative}: expected status marker: {marker}")

for markdown in root.rglob("*.md"):
    if any(part in {".git", "node_modules", "target"} for part in markdown.parts):
        continue
    text = markdown.read_text(encoding="utf-8")
    for raw_link in link_pattern.findall(text):
        link = raw_link.strip().split(maxsplit=1)[0].strip("<>")
        if not link or link.startswith(("#", "http://", "https://", "mailto:")):
            continue
        relative = unquote(link.split("#", 1)[0])
        target = (markdown.parent / relative).resolve()
        try:
            target.relative_to(root.resolve())
        except ValueError:
            errors.append(f"{markdown.relative_to(root)}: link leaves repository: {link}")
            continue
        if not target.exists():
            errors.append(f"{markdown.relative_to(root)}: missing target: {link}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
PY

while IFS= read -r file; do
  case "$file" in
    scripts/*.sh)
      [[ -x "$file" ]] || {
        printf 'Shell script must be executable: %s\n' "$file" >&2
        exit 1
      }
      ;;
    *)
      [[ ! -x "$file" ]] || {
        printf 'Unexpected executable file: %s\n' "$file" >&2
        exit 1
      }
      ;;
  esac
done < <(git ls-files)

[[ -x "scripts/validate-docs.sh" ]] || {
  printf 'Shell script must be executable: scripts/validate-docs.sh\n' >&2
  exit 1
}

git diff --check
printf 'Documentation validation passed.\n'
