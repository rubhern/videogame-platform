# VideoGame Platform

## Purpose

VideoGame Platform is a product-oriented platform for discovering, tracking,
rating, and analysing video games. It is also a long-term learning environment
for solution architecture and technical leadership.

## Current phase

**Product alignment is closed.** The approved Product Brief, first journey, MVP
release cut, clickable prototype, and accepted simulated usability round form the
closed phase record for this private learning project. The journey gate is `PASS`:
four of five sessions completed unaided and a focused simulated regression resolved
the blocking personal-rating state.

The minimum provider-independent domain model, application use-case contract, MVP
solution architecture, and REST API conventions are approved. The OpenAPI contract
for one vertical slice is defined. Prototype and usability work remain closed for
the current learning objective.

The minimum platform and delivery design is approved. The persistent private `dev`
environment uses OCI Always Free with private Tailscale access. PostgreSQL and
versioned forward migrations, Keycloak, GitHub Actions/GHCR, and
OpenTelemetry-compatible instrumentation are accepted through ADR-0005 to ADR-0009.

**Phase 1 — MVP solution definition is complete.** The approved
[technology baseline](docs/architecture/technology/mvp-technology-baseline.md) and
ADR-0010 through ADR-0012 select the supported implementation stack. Application
implementation is now active at the walking-skeleton gate: prove local, CI,
`linux/amd64`, and `linux/arm64` compatibility before feature expansion. No remote
infrastructure has been implemented yet.

The initial backend and frontend walking-skeleton foundations are executable locally.
The backend proves the Java/Spring module boundaries and Actuator health; the
frontend proves the React/TypeScript/Vite baseline, complete OpenAPI type generation,
and its static-analysis, component, browser-smoke, and production-build paths. The
local PostgreSQL 18 and Keycloak 26.7 topology is also executable with isolated roles,
reproducible identity configuration, health checks, persistent disposable data, and
verified AMD64/ARM64 dependency manifests. This does not close the broader
compatibility gate: application persistence, BFF identity integration, CI, combined
packaging, and application multi-architecture evidence remain outstanding.

The initial architecture diagram baseline is established through ADR-0013. Approved
documents and ADRs remain authoritative; Structurizr owns shared C4 views, Mermaid
owns focused code-based views, and diagrams.net is reserved for polished derived
communication views.

- Treat the approved MVP boundary, IGDB decision, accepted limitations, and private
  non-commercial release mode as the current product constraints.
- Use the approved Java/Spring, PostgreSQL/Flyway, and React/TypeScript/Vite baseline;
  do not assume that a public release, business model, paid service, or distributed
  architecture has been approved.
- Preserve the zero recurring-cost constraint: use only currently eligible free
  resources and stop rather than silently provisioning a paid alternative.
- Prove the approved baseline through the smallest executable walking skeleton before
  expanding feature implementation or provisioning remote infrastructure.
- Keep proposed product decisions labelled as hypotheses until evidence or an
  explicit owner decision supports them.
- Use the story map as the current planning boundary; prefer minimum contracts and
  one vertical slice over a detailed backlog or broad architecture.

## Sources of truth

- Initial vision: `docs/reference/video-game-platform-vision.pdf`
- Product Brief: `docs/product/product-brief.md`
- Learning MVP story map: `docs/product/mvp-story-map.md`
- Approved domain model: `docs/architecture/domain/mvp-domain-model.md`
- Approved application use cases:
  `docs/architecture/application/mvp-use-cases.md`
- Approved MVP solution architecture:
  `docs/architecture/mvp-solution-architecture.md`
- Approved REST API conventions:
  `docs/architecture/api/api-conventions.md`
- Approved platform and delivery design:
  `docs/architecture/deployment/mvp-platform-and-delivery.md`
- Approved delivery lifecycle:
  `docs/development/delivery-lifecycle.md`
- Approved work-management baseline:
  `docs/development/work-management.md`
- Assumptions: `docs/product/assumptions.md`
- Open questions: `docs/product/open-questions.md`
- Glossary: `docs/product/glossary.md`
- Accepted simulated usability synthesis:
  `docs/research/simulated-round-synthesis.md`
- Tooling and Codex setup: `docs/development/codex-setup.md`
- Supported local development setup: `docs/development/local-setup.md`
- Architectural decisions, once required: `docs/decisions/`
- Approved technology baseline:
  `docs/architecture/technology/mvp-technology-baseline.md`
- Architecture diagram catalogue and ownership rules:
  `docs/architecture/diagrams/README.md`
- Accepted architectural decisions: `docs/decisions/0001-*.md` through
  `docs/decisions/0013-*.md`

When sources conflict, report the conflict instead of choosing silently.
Distinguish evidence, decisions, assumptions, and proposals.

## Product principles

- Resolve a meaningful user problem before designing the technical solution.
- Select one priority user and one primary journey for the MVP.
- Keep the MVP small enough to validate explicit hypotheses.
- Treat external-data licensing as a product and architecture constraint.
- Do not promote long-term vision capabilities into committed scope by default.

## Engineering principles

- Prefer simple, mature, maintainable technology.
- Deliver complete vertical slices when implementation begins.
- Avoid microservices and distributed infrastructure without documented need.
- Keep domain boundaries explicit and external providers isolated.
- Treat testing, security, observability, accessibility, delivery, and
  operations as part of product quality.
- Record significant, durable architecture decisions as ADRs.

## Working method

For substantial tasks:

1. Read the relevant sources of truth.
2. State the current understanding, assumptions, and material risks.
3. Propose the smallest coherent plan.
4. Make a focused change without unrelated refactoring.
5. Validate the result.
6. Update affected documentation.
7. Summarise changes, checks, risks, and next decisions.

Use English for repository documentation, code, identifiers, tests, comments,
and commit messages.

Whenever work references a GitHub issue, inspect the issue's GitHub Project item
and update its delivery status to match the workflow in
`docs/development/work-management.md`. Do not leave the board stale when work
starts, enters review or validation, returns for changes, or is completed.

## Validation

Run:

```bash
bash scripts/validate-prerequisites.sh
bash scripts/validate-docs.sh
npm ci
bash scripts/validate-openapi.sh
npm run frontend:verify
./mvnw clean verify
./mvnw -f tools/igdb-poc/pom.xml clean verify
```

OpenAPI validation requires the approved Node.js 24 line. The Maven command runs
with Java 25. The root Maven build verifies the backend, while the targeted Maven
command validates the isolated IGDB PoC using local fixtures only. Authenticated
provider calls remain manual and explicit.

## Permissions and safety

The project deliberately configures Codex for maximum local autonomy because it
is personal and currently non-critical. Full access does not remove the need to:

- inspect Git status and diffs before staging;
- preserve unrelated work;
- avoid committing secrets or personal data;
- verify destructive targets explicitly;
- prefer recoverable operations;
- validate before committing and pushing.

## Definition of done

A change is complete when:

- the requested outcome and applicable acceptance criteria are satisfied;
- relevant automated checks pass;
- sources of truth remain coherent;
- significant decisions and remaining risks are documented;
- no unrelated scope was added.
