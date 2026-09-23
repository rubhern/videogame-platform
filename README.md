# VideoGame Platform

VideoGame Platform is a Spanish-first web product for discovering recent and
upcoming video-game releases and keeping a personal rating for each game. It is also
a long-term learning project for product, architecture, delivery, and technical
leadership, developed and operated by one person.

The private, non-commercial learning MVP was accepted end to end in private `dev`
and tagged `v0.1.0`: release discovery, bounded catalogue search, game details,
same-origin Keycloak-backed authentication, personal ratings, `Mis puntuaciones`,
operator-triggered IGDB synchronization, owner-triggered deployment, and proven
backup/restore. Further work is post-MVP evolution or maintenance, tracked in
GitHub Issues.

The system is one same-origin React application and Java/Spring modular monolith,
with PostgreSQL, Flyway, Keycloak, and provider-independent local catalogue data.
Public production, paid infrastructure, distributed architecture, and live
request-path provider calls are not approved.

## Start locally

The supported workstation is Ubuntu 24.04 on WSL2 with Docker Desktop integration.

```bash
bash scripts/validate-prerequisites.sh
bash scripts/local-dependencies.sh up
bash scripts/local-dependencies.sh application
```

[Local setup](docs/development/local-setup.md) owns the topology, the separate
backend/frontend development loops, and the reset boundary; the
[backend](backend/README.md) and [frontend](frontend/README.md) READMEs own their
module commands.

## Repository map

| Path | Responsibility |
|---|---|
| `backend/` | Java/Spring modular monolith, BFF/API, persistence adapters, Postman assets, and backend tests |
| `frontend/` | React/TypeScript SPA and browser/component tests |
| `deploy/private-dev/` | Operator entry point for the private `dev` host |
| `docs/` | Product, architecture, decisions, development, research; [`docs/README.md`](docs/README.md) is the map |
| `scripts/` | Executable development and validation entry points |
| `tools/igdb-poc/` | Isolated provider proof-of-concept tool |
| `AGENTS.md`, `CLAUDE.md`, `.agents/skills/` | AI agent instructions and skills; [AI assistance](docs/development/ai-assistance.md) owns the configuration |

## Where to look

- Product scope and journey: [Product Brief](docs/product/product-brief.md) and
  [MVP story map](docs/product/mvp-story-map.md).
- System structure and rules: [solution architecture](docs/architecture/mvp-solution-architecture.md),
  [domain model](docs/architecture/domain/mvp-domain-model.md),
  [use cases](docs/architecture/application/mvp-use-cases.md), and
  [ADRs](docs/decisions/README.md).
- HTTP contract: [OpenAPI](docs/architecture/api/openapi.yaml).
- Environments and operation: [platform design](docs/architecture/deployment/mvp-platform-and-delivery.md)
  and [operations runbook](docs/development/operations-runbook.md).
- How changes are made and validated: [delivery lifecycle](docs/development/delivery-lifecycle.md)
  and [continuous integration](docs/development/continuous-integration.md).

For a documentation-only change run `bash scripts/validate-docs.sh`; the CI guide
maps every other focused check.
