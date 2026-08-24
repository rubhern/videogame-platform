# Documentation map

This page defines documentation ownership. A document may link to another area but
must not restate its contract or become a second source of truth.

## Canonical ownership

| Information | Canonical source | Other documents may contain |
|---|---|---|
| Project orientation and current implementation summary | [Root README](../README.md) | Links and a short local context |
| Backend/frontend immediate use | [Backend README](../backend/README.md) and [frontend README](../frontend/README.md) | Module-specific commands and troubleshooting |
| Product user, problem, value, MVP boundary, evidence standard, and risks | [Product Brief](product/product-brief.md) | Links to evidence and decisions |
| Current journey and release cut | [MVP story map](product/mvp-story-map.md) | Acceptance criteria, not implementation tasks |
| Product uncertainty and terminology | [Assumptions](product/assumptions.md), [resolved questions](product/open-questions.md), and [glossary](product/glossary.md) | No architecture or workflow rules |
| Domain concepts and invariants | [Domain model](architecture/domain/mvp-domain-model.md) | Conceptual rules, never schemas or HTTP shapes |
| Application operations, guarantees, and stable errors | [Use cases](architecture/application/mvp-use-cases.md) | No HTTP or framework mechanics |
| Current system structure and dependency boundaries | [Solution architecture](architecture/mvp-solution-architecture.md) | Links to durable ADRs and specific contracts |
| Browser HTTP wire contract | [OpenAPI](architecture/api/openapi.yaml) | Paths, schemas, examples, security, and responses |
| Cross-operation HTTP policy | [API conventions](architecture/api/api-conventions.md) | Rules not already clear in OpenAPI |
| Approved stack and technology policy | [Technology baseline](architecture/technology/mvp-technology-baseline.md) | Version lines and selection policy; exact versions stay in manifests |
| Environments, topology, deployment, secrets, backup, and recovery behaviour | [Platform and delivery design](architecture/deployment/mvp-platform-and-delivery.md) | No human workflow or copy of scripts |
| Durable architecture decisions | [ADRs](decisions/README.md) | Context, decision, alternatives, consequences, and revisit triggers |
| Development and delivery procedures | [Development docs](development/README.md) | Stable workflows; scripts/configuration own executable detail |
| Human change flow, gates, versioning, and Definition of Done | [Delivery lifecycle](development/delivery-lifecycle.md) | No deployment-command duplication |
| Historical evidence and spikes | [Research](research/README.md) | Method, evidence, limitations, conclusion; never current operations |
| Original direction | [Vision PDF](reference/video-game-platform-vision.pdf) | Historical input, narrowed by approved product records |

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

Run `bash scripts/validate-docs.sh` after moving, deleting, or linking documentation.
