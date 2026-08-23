#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

required_files=(
  ".env.example"
  ".dockerignore"
  "Dockerfile"
  "backend/.env.example"
  "compose.yaml"
  "docker/keycloak/import/videogame-platform-realm.json"
  "docker/postgres/init/001-create-local-databases.sh"
  ".mvn/wrapper/maven-wrapper.properties"
  "AGENTS.md"
  "README.md"
  "backend/README.md"
  "backend/postman/README.md"
  "backend/postman/actuator.postman_collection.json"
  "backend/postman/catalogue-releases.postman_collection.json"
  "backend/postman/local.postman_environment.json"
  "frontend/README.md"
  "frontend/package.json"
  "frontend/src/shared/api/generated/schema.d.ts"
  "frontend/tsconfig.app.json"
  "frontend/tsconfig.node.json"
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
  "docs/development/backend.md"
  "docs/development/backend-openapi-generation.md"
  "docs/development/continuous-integration.md"
  "docs/development/container-image.md"
  "docs/development/database-migrations.md"
  "docs/development/local-dependencies.md"
  "docs/development/local-setup.md"
  "docs/development/frontend.md"
  "docs/development/openapi-validation.md"
  "docs/development/openapi-web-documentation.md"
  "docs/development/release-api.md"
  "docs/development/delivery-lifecycle.md"
  "docs/development/work-management.md"
  "docs/architecture/domain/mvp-domain-model.md"
  "docs/architecture/application/mvp-use-cases.md"
  "docs/architecture/mvp-solution-architecture.md"
  "docs/architecture/api/api-conventions.md"
  "docs/architecture/api/openapi.yaml"
  "docs/architecture/api/reference/index.html"
  "docs/architecture/deployment/mvp-platform-and-delivery.md"
  "docs/architecture/technology/mvp-technology-baseline.md"
  "docs/architecture/diagrams/README.md"
  "docs/architecture/diagrams/structurizr/workspace.dsl"
  "docs/architecture/diagrams/structurizr/workspace.json"
  "docs/architecture/diagrams/structurizr/structurizr.properties"
  "docs/architecture/diagrams/diagrams-net/private-dev-deployment.drawio"
  "docs/architecture/diagrams/mermaid/module-context-map.mmd"
  "docs/architecture/diagrams/mermaid/hexagonal-dependency-rules.mmd"
  "docs/architecture/diagrams/mermaid/authenticate-and-create-rating-sequence.mmd"
  "docs/architecture/diagrams/mermaid/synchronize-bounded-catalogue-sequence.mmd"
  "docs/architecture/diagrams/mermaid/catalogue-persistence-model.mmd"
  "docs/architecture/diagrams/mermaid/delivery-pipeline.mmd"
  "docs/architecture/diagrams/scripts/render-mermaid.sh"
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
  "docs/decisions/0013-use-model-backed-and-purpose-specific-architecture-diagrams.md"
  "docs/decisions/0014-generate-backend-http-contracts-from-openapi.md"
  "package.json"
  "package-lock.json"
  "mvnw"
  "redocly.yaml"
  "scripts/validate-openapi.sh"
  "scripts/validate-migrations.sh"
  "scripts/validate-actions.sh"
  "scripts/detect-ci-changes.sh"
  "scripts/test-ci-change-detection.sh"
  "scripts/verify-ci-results.sh"
  "scripts/validate-browser.sh"
  "scripts/validate-container-image.sh"
  "scripts/local-dependencies.sh"
  "scripts/validate-prerequisites.sh"
  "scripts/build-openapi-docs.sh"
  "tools/openapi-validation/syntax.redocly.yaml"
  "tools/openapi-validation/schemas.redocly.yaml"
  "tools/openapi-validation/examples.redocly.yaml"
  "tools/openapi-validation/normalize-generated-html.mjs"
  ".agents/skills/README.md"
  ".agents/skills/product-brief-review/SKILL.md"
  ".agents/skills/scalability-by-design/SKILL.md"
  ".agents/skills/videogame-platform-backend-development/SKILL.md"
  ".agents/skills/videogame-platform-frontend-development/SKILL.md"
  ".agents/skills/java-springboot/SKILL.md"
  ".agents/skills/architecture-patterns/SKILL.md"
  ".agents/skills/tdd/SKILL.md"
  ".agents/skills/vercel-react-best-practices/SKILL.md"
  ".agents/skills/vercel-composition-patterns/SKILL.md"
  ".agents/skills/react-testing/SKILL.md"
  ".agents/skills/frontend-accessibility-best-practices/SKILL.md"
  ".github/dependabot.yml"
  ".github/workflows/dependency-submission.yml"
  ".github/workflows/build-and-verify.yml"
  ".github/workflows/security.yml"
  "backend/src/main/resources/db/migration/V20260809_120000__create_catalogue_schema.sql"
  "backend/src/main/resources/db/migration/V20260813_120000__add_game_external_references.sql"
  "backend/src/main/resources/db/migration/V20260818_120000__constrain_release_date_year_range.sql"
  "backend/src/main/resources/db/dev-seed/V20260809_130000__seed_bounded_prototype_catalogue.sql"
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
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET
from urllib.parse import unquote

root = pathlib.Path(sys.argv[1])
link_pattern = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
skill_resource_pattern = re.compile(
    r"(?:`|@)((?:assets|references|rules|scripts)/[A-Za-z0-9._/-]+)"
)
errors = []

skills_root = root / ".agents/skills"
skills_registry = (skills_root / "README.md").read_text(encoding="utf-8")
vendored_skill_names = set(
    re.findall(
        r"^\| `([^`]+)` .* \| `vendored-unmodified` \|$",
        skills_registry,
        flags=re.MULTILINE,
    )
)

json_documents = (
    "backend/postman/actuator.postman_collection.json",
    "backend/postman/catalogue-releases.postman_collection.json",
    "backend/postman/local.postman_environment.json",
    "docker/keycloak/import/videogame-platform-realm.json",
)

for relative in json_documents:
    document = root / relative
    try:
        json.loads(document.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        errors.append(f"{relative}: invalid JSON: {error}")

maven_namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
semver_pattern = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)
try:
    reactor_pom = ET.parse(root / "pom.xml").getroot()
    backend_pom = ET.parse(root / "backend/pom.xml").getroot()
    reactor_version = reactor_pom.findtext("m:version", namespaces=maven_namespace)
    backend_parent_version = backend_pom.findtext(
        "m:parent/m:version", namespaces=maven_namespace
    )
    if reactor_version is None or semver_pattern.fullmatch(reactor_version) is None:
        errors.append("pom.xml: project version must be a valid Semantic Version")
    if backend_parent_version != reactor_version:
        errors.append(
            "backend/pom.xml: parent version must match the backend reactor version"
        )
    if reactor_version is not None:
        expected_jar = f"videogame-platform-backend-{reactor_version}.jar"
        for relative in ("backend/README.md", "docs/development/backend.md"):
            if expected_jar not in (root / relative).read_text(encoding="utf-8"):
                errors.append(
                    f"{relative}: expected current backend artefact {expected_jar}"
                )
except (OSError, ET.ParseError) as error:
    errors.append(f"Maven version validation failed: {error}")

realm_path = root / "docker/keycloak/import/videogame-platform-realm.json"
try:
    realm = json.loads(realm_path.read_text(encoding="utf-8"))
    clients = [
        client for client in realm.get("clients", [])
        if client.get("clientId") == "videogame-platform-bff"
    ]
    users = realm.get("users", [])
    if realm.get("realm") != "videogame-platform" or not realm.get("enabled"):
        errors.append("Keycloak realm must define the enabled videogame-platform realm")
    if len(clients) != 1:
        errors.append("Keycloak realm must define exactly one videogame-platform-bff client")
    else:
        client = clients[0]
        expected_client_values = {
            "publicClient": False,
            "standardFlowEnabled": True,
            "implicitFlowEnabled": False,
            "directAccessGrantsEnabled": False,
            "serviceAccountsEnabled": False,
            "secret": "${KEYCLOAK_BFF_CLIENT_SECRET}",
        }
        for key, expected in expected_client_values.items():
            if client.get(key) != expected:
                errors.append(f"Keycloak BFF client {key} must be {expected!r}")
        if client.get("attributes", {}).get("pkce.code.challenge.method") != "S256":
            errors.append("Keycloak BFF client must require PKCE S256")
    if len(users) != 1 or users[0].get("username") != "${LOCAL_TEST_USER_USERNAME}":
        errors.append("Keycloak realm must define exactly one environment-backed local test user")
    else:
        credentials = users[0].get("credentials", [])
        if len(credentials) != 1 or credentials[0].get("value") != "${LOCAL_TEST_USER_PASSWORD}":
            errors.append("Keycloak local test-user password must remain an environment placeholder")
except (OSError, UnicodeError, json.JSONDecodeError):
    pass

infrastructure_env_example = (root / ".env.example").read_text(encoding="utf-8")
for secret_variable in (
    "POSTGRES_ADMIN_PASSWORD",
    "APPLICATION_DB_PASSWORD",
    "APPLICATION_MIGRATION_DB_PASSWORD",
    "KEYCLOAK_DB_PASSWORD",
    "KEYCLOAK_ADMIN_PASSWORD",
    "KEYCLOAK_BFF_CLIENT_SECRET",
    "LOCAL_TEST_USER_PASSWORD",
):
    if f"{secret_variable}=GENERATE_ME" not in infrastructure_env_example.splitlines():
        errors.append(f".env.example: {secret_variable} must use the GENERATE_ME placeholder")

backend_env_example = (root / "backend/.env.example").read_text(encoding="utf-8")
for secret_variable in (
    "APPLICATION_DB_PASSWORD",
    "APPLICATION_MIGRATION_DB_PASSWORD",
    "KEYCLOAK_BFF_CLIENT_SECRET",
):
    if f"{secret_variable}=GENERATE_ME" not in backend_env_example.splitlines():
        errors.append(
            f"backend/.env.example: {secret_variable} must use the GENERATE_ME placeholder"
        )

def env_variable_names(content):
    return {
        line.split("=", 1)[0]
        for line in content.splitlines()
        if line and not line.startswith("#") and "=" in line
    }

expected_infrastructure_variables = {
    "COMPOSE_PROJECT_NAME",
    "POSTGRES_PORT",
    "POSTGRES_ADMIN_PASSWORD",
    "APPLICATION_DB_PASSWORD",
    "APPLICATION_MIGRATION_DB_PASSWORD",
    "KEYCLOAK_DB_PASSWORD",
    "KEYCLOAK_HTTP_PORT",
    "KEYCLOAK_MANAGEMENT_PORT",
    "KEYCLOAK_HOSTNAME",
    "KEYCLOAK_ADMIN_USERNAME",
    "KEYCLOAK_ADMIN_PASSWORD",
    "KEYCLOAK_BFF_CLIENT_SECRET",
    "LOCAL_TEST_USER_USERNAME",
    "LOCAL_TEST_USER_PASSWORD",
}
expected_backend_variables = {
    "SPRING_PROFILES_ACTIVE",
    "SERVER_PORT",
    "LOGGING_LEVEL_COM_VIDEOGAMEPLATFORM",
    "APPLICATION_DB_URL",
    "APPLICATION_DB_USERNAME",
    "APPLICATION_DB_PASSWORD",
    "APPLICATION_FLYWAY_ENABLED",
    "APPLICATION_MIGRATION_DB_URL",
    "APPLICATION_MIGRATION_DB_USERNAME",
    "APPLICATION_MIGRATION_DB_PASSWORD",
    "SPRING_FLYWAY_LOCATIONS",
    "KEYCLOAK_BFF_CLIENT_SECRET",
    "OIDC_ISSUER_URI",
    "APPLICATION_SESSION_TIMEOUT",
    "APPLICATION_SESSION_COOKIE_NAME",
    "APPLICATION_SESSION_COOKIE_SECURE",
    "CATALOGUE_RELEASES_RECENT_WINDOW_MONTHS",
    "CATALOGUE_RELEASES_UPCOMING_WINDOW_MONTHS",
    "CATALOGUE_RELEASES_FRESHNESS_THRESHOLD",
    "CATALOGUE_RELEASES_CACHE_CONTROL",
    "TELEMETRY_OTLP_TRACES_ENABLED",
    "TELEMETRY_OTLP_TRACES_ENDPOINT",
    "TELEMETRY_OTLP_METRICS_ENABLED",
    "TELEMETRY_OTLP_METRICS_ENDPOINT",
    "TELEMETRY_OTLP_METRICS_STEP",
    "TELEMETRY_TRACING_SAMPLING_PROBABILITY",
}
for path, actual, expected in (
    (".env.example", env_variable_names(infrastructure_env_example), expected_infrastructure_variables),
    ("backend/.env.example", env_variable_names(backend_env_example), expected_backend_variables),
):
    if actual != expected:
        missing = sorted(expected - actual)
        unexpected = sorted(actual - expected)
        errors.append(f"{path}: environment scope mismatch; missing={missing}, unexpected={unexpected}")

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
    "docs/decisions/0013-use-model-backed-and-purpose-specific-architecture-diagrams.md": "Accepted",
    "docs/decisions/0014-generate-backend-http-contracts-from-openapi.md": "Accepted",
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
    relative_markdown = markdown.relative_to(root)
    vendored_skill_root = None
    if (
        len(relative_markdown.parts) >= 4
        and relative_markdown.parts[:2] == (".agents", "skills")
        and relative_markdown.parts[2] in vendored_skill_names
    ):
        vendored_skill_root = skills_root / relative_markdown.parts[2]

    if vendored_skill_root is not None and markdown.name != "SKILL.md":
        continue

    for raw_link in link_pattern.findall(text):
        link = raw_link.strip().split(maxsplit=1)[0].strip("<>")
        if not link or link.startswith(("#", "http://", "https://", "mailto:")):
            continue
        relative = unquote(link.split("#", 1)[0])
        target = (markdown.parent / relative).resolve()
        if vendored_skill_root is not None:
            try:
                target.relative_to(vendored_skill_root.resolve())
            except ValueError:
                # Unmodified upstream entrypoints may link to optional sibling
                # skill packages that are not dependencies of this vendored skill.
                continue
        try:
            target.relative_to(root.resolve())
        except ValueError:
            errors.append(f"{relative_markdown}: link leaves repository: {link}")
            continue
        if not target.exists():
            errors.append(f"{relative_markdown}: missing target: {link}")

    if vendored_skill_root is not None:
        for relative in skill_resource_pattern.findall(text):
            target = vendored_skill_root / relative
            if not target.exists():
                errors.append(f"{relative_markdown}: missing skill resource: {relative}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
PY

while IFS= read -r file; do
  case "$file" in
    mvnw|scripts/*.sh|docker/postgres/init/*.sh|docs/architecture/diagrams/scripts/*.sh)
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

[[ -x "scripts/validate-prerequisites.sh" ]] || {
  printf 'Shell script must be executable: scripts/validate-prerequisites.sh\n' >&2
  exit 1
}

[[ -x "mvnw" ]] || {
  printf 'Maven Wrapper must be executable: mvnw\n' >&2
  exit 1
}

git diff --check
printf 'Documentation validation passed.\n'
