# VideoGame Platform

VideoGame Platform is a Spanish-first web product for discovering recent and
upcoming video-game releases and, in the approved MVP, recording and retrieving a
personal rating. It is also a long-term learning project for product, architecture,
delivery, and technical leadership.

The current release is private, non-commercial, and operated by one person. Product
alignment and solution definition are closed; implementation is active. The
repository currently proves the PostgreSQL-backed recent and upcoming release
discovery page, bounded catalogue search over canonical titles and approved aliases,
a packaged React frontend, and a real Keycloak-backed BFF session. Game details,
ratings, personal ratings, provider synchronization, and remote `dev` deployment
remain later slices of the approved journey. The reviewed private OCI Terraform stack
and its fail-closed zero-cost plan gate are implemented locally, but no remote
infrastructure has been provisioned.

The system is one same-origin React application and Java/Spring modular monolith,
with PostgreSQL, Flyway, Keycloak, and provider-independent local catalogue data.
Public production, paid infrastructure, distributed architecture, and live
request-path provider calls are not approved.

## Start locally

The supported workstation is Ubuntu 24.04 on WSL2 with the repository in the Linux
filesystem and Docker Desktop integration enabled.

```bash
bash scripts/validate-prerequisites.sh
bash scripts/local-dependencies.sh up
bash scripts/local-dependencies.sh verify
```

For separate backend and frontend development:

```bash
set -a
source backend/.env
set +a
APPLICATION_FLYWAY_ENABLED=true ./mvnw -pl backend spring-boot:run
npm ci
npm run frontend:dev
```

Vite runs at `http://localhost:5173` and proxies server-owned routes to the backend
at `http://localhost:8080`. Alternatively, run the packaged application and its
dependencies together:

```bash
bash scripts/local-dependencies.sh application
```

See [local setup](docs/development/local-setup.md), the
[backend README](backend/README.md), and the
[frontend README](frontend/README.md) for the maintained technical instructions.

## Repository map

| Path | Responsibility |
|---|---|
| `backend/` | Java/Spring modular monolith, BFF/API, persistence adapters, and backend tests |
| `frontend/` | React/TypeScript SPA and browser/component tests |
| `docs/product/` | Approved product direction, scope, assumptions, and terminology |
| `docs/architecture/` | Current structural, domain, application, API, technology, and platform design |
| `docs/decisions/` | Durable architecture decisions and their trade-offs |
| `docs/development/` | Development and delivery workflows |
| `docs/research/` | Historical evidence and bounded spikes, not operational instructions |
| `tools/igdb-poc/` | Isolated provider proof-of-concept tool |
| `scripts/` | Executable development and validation entry points |
| `infrastructure/terraform/` | Private OCI Resource Manager stack and hard cost/resource constraints |
| `AGENTS.md`, `CLAUDE.md` | Instructions for AI coding agents; `CLAUDE.md` imports `AGENTS.md` |
| `.agents/skills/` | Canonical copy of every agent skill |
| `.claude/`, `.codex/` | Per-agent configuration; `.claude/skills/` symlinks the canonical skills |

The [documentation map](docs/README.md) defines the canonical owner for every
documentation area and how conflicts are resolved. The
[AI assistance guide](docs/development/ai-assistance.md) owns the agent
configuration.

## Key sources of truth

- [Product Brief](docs/product/product-brief.md): user, problem, value, MVP boundary,
  evidence standard, and product risks.
- [MVP story map](docs/product/mvp-story-map.md): approved journey and release cut.
- [Domain model](docs/architecture/domain/mvp-domain-model.md): concepts and business
  invariants.
- [Application use cases](docs/architecture/application/mvp-use-cases.md):
  application operations, guarantees, and errors.
- [Solution architecture](docs/architecture/mvp-solution-architecture.md): current
  system structure and dependency rules.
- [OpenAPI](docs/architecture/api/openapi.yaml): browser-facing HTTP contract.
- [Platform and delivery design](docs/architecture/deployment/mvp-platform-and-delivery.md):
  environment and deployment behaviour.
- [Delivery lifecycle](docs/development/delivery-lifecycle.md): human workflow,
  validation selection, versioning, and completion rules.
- [ADRs](docs/decisions/): accepted durable decisions.

## Validation

Select the smallest check that can detect a regression in the changed area. For a
documentation-only change:

```bash
bash scripts/validate-docs.sh
```

Common focused entry points are:

```bash
bash scripts/validate-openapi.sh
npm run frontend:verify
./mvnw clean verify
bash scripts/validate-migrations.sh
bash scripts/validate-browser.sh
bash scripts/validate-identity.sh
bash scripts/validate-container-image.sh
```

The [continuous-integration guide](docs/development/continuous-integration.md) maps
these commands to CI. Pull-request CI supplies the complete affected-area gate;
trusted `main` CI supplies full repository integration evidence.
