# Documentation map

This page is the single index of maintained documentation and the ownership rule
behind it: every kind of information has one canonical owner, and any other document
links to it instead of restating it.

Records describe the approved and implemented state of the private, non-commercial
learning MVP (`v0.1.0`). Future capabilities and revisit triggers are labelled as such
and are not commitments.

## Canonical ownership

| Information | Canonical source | Other documents may contain |
|---|---|---|
| Project orientation and repository map | [Root README](../README.md) | Links and a short local context |
| Backend/frontend immediate use | [Backend README](../backend/README.md), [frontend README](../frontend/README.md), [Postman README](../backend/postman/README.md) | Module-specific commands and troubleshooting |
| Product user, problem, value, MVP boundary, evidence standard, and risks | [Product Brief](product/product-brief.md) | Links to evidence and decisions |
| Current journey and release cut | [MVP story map](product/mvp-story-map.md) | Acceptance criteria, not implementation tasks |
| Product assumptions, resolved questions, and terminology | [Assumptions and decisions](product/assumptions-and-decisions.md) and [glossary](product/glossary.md) | No architecture or workflow rules |
| Domain concepts and invariants | [Domain model](architecture/domain/mvp-domain-model.md) | Conceptual rules, never schemas or HTTP shapes |
| Application operations, guarantees, and stable errors | [Use cases](architecture/application/mvp-use-cases.md) | No HTTP or framework mechanics |
| Current system structure and dependency boundaries | [Solution architecture](architecture/mvp-solution-architecture.md) | Links to durable ADRs and specific contracts |
| Browser HTTP wire contract | [OpenAPI](architecture/api/openapi.yaml) | Paths, schemas, examples, security, and responses |
| Cross-operation HTTP policy | [API conventions](architecture/api/api-conventions.md) | Rules not already clear in OpenAPI |
| Approved stack and technology policy | [Technology baseline](architecture/technology/mvp-technology-baseline.md) | Version lines and selection policy; exact versions stay in manifests |
| Environments, topology, deployment, secrets, backup, and recovery policy | [Platform and delivery design](architecture/deployment/mvp-platform-and-delivery.md) | No human workflow or copy of scripts |
| Private-dev operator commands and host procedures | [Private-dev README](../deploy/private-dev/README.md) | Compose, scripts, and collector config own the mechanics |
| Architecture diagrams | [Diagram catalogue](architecture/diagrams/README.md) | One question per view; owning records stay authoritative |
| Durable architecture decisions | [ADRs](decisions/README.md) | Context, decision, alternatives, consequences, and revisit triggers |
| Local workstation, topology, and reset boundary | [Local setup](development/local-setup.md) | Compose and `.env.example` own executable detail |
| Frontend visual language and mandatory screen/component rules | [Frontend design guidelines](development/frontend-design.md) | Links and local implementation context, never duplicated visual rules |
| OpenAPI change sequence and generation boundaries | [OpenAPI workflow](development/openapi.md) | Manifests own generator options |
| Flyway authoring, privilege, seed, and validation policy | [Database migrations](development/database-migrations.md) | SQL owns the schema |
| Health, correlation, logs, metrics, tracing, and telemetry safety | [Observability](development/observability.md) | `application.yaml` owns runtime settings |
| CI gate contract and command mapping | [Continuous integration](development/continuous-integration.md) | Workflows and scripts own job mechanics |
| Proven operational procedures and evidence boundary | [Operations runbook](development/operations-runbook.md) | Links to the executable owner of each command; never policy |
| Human change flow, risk, versioning, validation policy, and Definition of Done | [Delivery lifecycle](development/delivery-lifecycle.md) | No deployment-command duplication |
| GitHub Issue/Project statuses and fields | [Work management](development/work-management.md) | The live Project is authoritative for status |
| AI agent configuration, skills, and external-skill provenance | [AI assistance](development/ai-assistance.md) | `AGENTS.md` and `CLAUDE.md` own the instructions themselves |
| Historical evidence, spikes, and point-in-time reviews | [Research](research/README.md) | Method, evidence, limitations, conclusion; never current operations |
| Original direction | [Vision PDF](reference/video-game-platform-vision.pdf) | Historical Spanish vision, translated to English on 2026-07-24; narrowed by the approved product records |

## Authority and maintenance rules

Approved product/architecture records and ADRs outrank explanatory READMEs. OpenAPI,
Flyway SQL, manifests, configuration, and scripts outrank prose for the executable
details they define. If authoritative sources conflict, report and resolve the
conflict; do not choose silently.

Avoid patch-level dependency versions, command internals, generated schema detail,
or CI job mechanics in prose when a maintained executable source already expresses
them. Keep historical evidence only when it explains a current decision or a
reopening condition. Git history, issue timelines, and CI run logs are not copied
into evergreen documentation.

A new document needs a distinct long-lived responsibility, an intended consumer, a
canonical owner in the table above, and a reason it cannot fit in an existing source.

Run `bash scripts/validate-docs.sh` after moving, deleting, or linking documentation.
