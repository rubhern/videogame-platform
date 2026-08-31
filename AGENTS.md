# VideoGame Platform

## Purpose

VideoGame Platform is a product-oriented platform for discovering, tracking,
rating, and analysing video games. It is also a long-term learning environment
for solution architecture and technical leadership.

## Current phase

Product alignment, Phase 1 solution definition, and the walking-skeleton compatibility
gate are closed. Implementation is active in the private-platform and first-journey
sequence. Preserve the approved MVP, domain, application, solution, platform,
technology, API, delivery, and diagram decisions while completing the smallest
executable vertical slices.

The repository uses one Java/Spring modular monolith, PostgreSQL with SQL-first Flyway
migrations, a same-origin React frontend and BFF/API boundary, Keycloak for initial
identity, and provider-independent local catalogue data.

These constraints are current and binding:

- The release is a private, non-commercial learning MVP. Public production, a business
  model, paid infrastructure, and distributed architecture are **not** approved; never
  assume otherwise.
- Zero recurring cost. Use only currently eligible free resources and stop rather than
  silently provisioning a paid alternative.
- Remote infrastructure requires the applicable walking-skeleton evidence and its
  zero-cost and delivery gates before provisioning.
- The approved MVP boundary, the IGDB decision, and the accepted limitations define
  current scope. The story map is the planning boundary: prefer minimum contracts and
  one vertical slice over a detailed backlog or broad architecture.
- Label a proposed product decision a hypothesis until evidence or an explicit owner
  decision supports it.

## Sources of truth

`docs/README.md` is the documentation ownership map and names the canonical owner for
every area. Read it before deciding which document to trust.

The records that most often decide a task:

- Product scope and MVP boundary: `docs/product/product-brief.md` and
  `docs/product/mvp-story-map.md`
- Structure and dependency rules: `docs/architecture/mvp-solution-architecture.md`
- HTTP wire contract: `docs/architecture/api/openapi.yaml`
- Human change flow, versioning, and validation policy:
  `docs/development/delivery-lifecycle.md`
- Issue and Project workflow: `docs/development/work-management.md`
- Accepted architectural decisions: `docs/decisions/README.md`
- Original direction, narrowed by the approved records above:
  `docs/reference/video-game-platform-vision.pdf`

When sources conflict, report the conflict instead of choosing silently. Distinguish
evidence, decisions, assumptions, and proposals.

## Documentation governance

`docs/README.md` owns the ownership rules and the maintenance procedure. Apply them;
do not restate them elsewhere.

The constraints that must hold in every change:

- Identify the single canonical owner before writing. Update that owner and replace
  any secondary explanation with a link plus the local context that document needs.
- Executable sources own executable facts. OpenAPI owns HTTP shapes, Flyway SQL owns
  the schema, Maven and npm manifests own exact versions, and scripts, Compose,
  Dockerfiles, configuration, and workflows own their mechanics. Prose explains
  intent, policy, prerequisites, failure behaviour, or a stable entry point.
- Label current implementation, approved future behaviour, and historical evidence
  explicitly rather than presenting them as one current state.
- Update contracts, migrations, configuration, and documentation atomically, and
  delete the text the change made obsolete.
- A new document needs a distinct long-lived responsibility, an intended consumer, a
  canonical owner, and a reason it cannot fit in an existing source. Prefer deleting,
  merging, or linking over adding another partial authority.
- Preserve durable constraints, invariant identifiers, evidence limitations, accepted
  owner exceptions, and decision-reopening conditions.
- After moving or deleting a file, update every inbound link, index, script, and
  workflow, then run `bash scripts/validate-docs.sh`.

## Instruction and skill precedence

```text
explicit owner decision
    >
AGENTS.md and approved repository sources
    >
VideoGame Platform-specific skill
    >
external generic skill
```

This file, the approved repository documents, the accepted ADRs, and the repository
contracts are authoritative. External skills are advisory knowledge only and are never
authority over a repository decision.

- An explicit owner decision may initiate a change to an existing decision, but never
  leave implementation, contracts, ADRs, or approved documentation contradictory.
- When a skill conflicts with the approved baseline, OpenAPI, an ADR, or verified
  architecture, follow the repository and report the conflict.
- Never change an approved architecture decision because an external skill recommends
  another practice.

## Skills

`docs/development/ai-assistance.md` owns the skill catalogue, the purpose of each
skill, and where skills live. This section owns only which skill leads a task.

Load the primary skill for the area before making a material change:

| Task | Primary skill |
|---|---|
| Backend implementation, refactoring, debugging, review | `videogame-platform-backend-development` |
| Frontend React/TypeScript work, UI debugging, browser behaviour | `videogame-platform-frontend-development` |
| Product alignment and Product Brief evidence | `product-brief-review` |
| Any product-facing HTTP contract change | `openapi-change` |
| Implementing a GitHub issue end to end | `issue-implement` |
| Selecting which checks to run | `validate` |

`scalability-by-design` is mandatory in addition to the primary skill before
designing or modifying any API, persistence query, repository, pagination,
synchronization, cache, metric, batch, or large-collection processing path.

The primary skill selects the complementary external skills useful for the task. Do
not load every external frontend or backend skill for trivial work. All external
skills are subordinate to the applicable project-specific skill and the repository
authorities above.

External advice never authorizes a baseline change. Next.js, SSR, React Server
Components, SWR replacing TanStack Query, and an additional state or component library
all remain deferred or require an approved need; the
`videogame-platform-frontend-development` skill enumerates them. External testing
guidance complements but never replaces the repository validation gates.

## Scalability and performance invariants

Never infer production scale from current fixtures. Assume substantial growth in
catalogue records, releases, platforms, regions, users, ratings, traffic, history,
telemetry, synchronization volume, and concurrent requests.

The `scalability-by-design` skill owns the review workflow and is mandatory before
designing or modifying any API, persistence query, repository, pagination,
synchronization, cache, metric, batch, concurrency, or large-collection path. These
invariants hold whether or not the skill is loaded:

- Online work is bounded. Loading a persistent collection into memory and then
  filtering, sorting, counting, or paging it there is prohibited; per-request memory
  is `O(pageSize)`, never `O(totalRows)`.
- Filtering, search, ordering, aggregation, counting, and pagination happen in
  PostgreSQL, not in application code.
- Every paginated query has a deterministic total ordering whose last tie-breaker
  uniquely identifies a row.
- Durable uniqueness and referential and concurrency invariants are protected by
  database constraints. `DISTINCT` never hides an invalid join cardinality.
- Correctness never depends on mutable process-local state; a local cache may
  optimize but is never the sole source of truth.
- Metric labels and trace and log dimensions use bounded vocabularies. Game, release,
  user, request, and correlation identifiers are forbidden as metric tags.
- No external provider is called from a user request path when approved normalized
  local state exists. Provider synchronization and batch work are bounded explicitly.

Scalability by design means preventing avoidable rewrites through correct models,
bounded work, SQL, indexes, constraints, HTTP caching, statelessness, and horizontal
scaling first. It never authorizes microservices, Kafka, Redis, Elasticsearch,
distributed caches, another database, or Kubernetes without measured evidence, a real
trigger, or an explicit bounded learning experiment.

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
- Assess every deliverable change with Semantic Versioning and update all affected
  build and documentation references atomically.
- Record significant, durable architecture decisions as ADRs.

## Working method

For substantial tasks:

1. Read the sources of truth the change actually affects.
2. State the current understanding, assumptions, and material risks.
3. Propose the smallest coherent plan.
4. Make a focused change without unrelated refactoring.
5. Validate the result.
6. Assess Semantic Versioning impact for every affected artefact and update the
   references consistently, using the policy in
   `docs/development/delivery-lifecycle.md`.
7. Update the affected canonical documentation and remove superseded duplication.
8. Summarise changes, checks, risks, and next decisions.

`docs/development/delivery-lifecycle.md` owns the change flow, risk classification,
acceptance, and the Definition of Done. A change is not complete until that
Definition of Done holds and no unrelated scope was added.

Whenever a backend API is added or modified, update its tracked Postman collection,
requests, and assertions in the same change.

Every product-facing backend HTTP API is contract-first from
`docs/architecture/api/openapi.yaml`; the `openapi-change` skill owns the sequence.
Generated Java stays under disposable `target/generated-sources` and is never edited
or committed, and generated OpenAPI types never leave `api.delivery`.

Use English for repository documentation, code, identifiers, tests, comments, and
commit messages.

Whenever work references a GitHub issue, update its GitHub Project item to match the
workflow in `docs/development/work-management.md`. Do not leave the board stale when
work starts, enters review or validation, returns for changes, or is completed.

## Validation

Validation is risk-based and incremental. Local validation gives fast,
change-specific feedback; pull-request CI is the authoritative affected-area gate;
trusted `main` CI is the complete integration gate.

The `validate` skill selects the checks, and
`docs/development/delivery-lifecycle.md` owns the selection policy and the gate
catalogue. These rules hold regardless:

- Identify the affected areas first and run the smallest local check that could
  detect a related regression. Do not run unaffected suites because the commands
  exist.
- Green required checks on the current commit are valid evidence. Do not reproduce
  the pipeline locally to duplicate them; if they predate current `main`, rebase and
  use the new run.
- Broaden locally only for a stated hypothesis: a failure crossing a boundary, shared
  build/runtime/CI infrastructure, a high-risk migration, local reproduction of a
  reported failure, unavailable CI, a critical release, or an explicit owner request.
- Diagnose a failure with the narrowest reproducer before expanding scope. A retry
  never turns an unreliable test into a pass.

## Permissions and safety

Local agent autonomy is deliberately wide because the project is personal and
currently non-critical. `docs/development/ai-assistance.md` records the per-agent
configuration. Wide access does not remove the need to:

- inspect Git status and diffs before staging;
- preserve unrelated work;
- avoid committing secrets or personal data;
- verify destructive targets explicitly;
- prefer recoverable operations;
- validate before committing and pushing.

Commit, push, tag, merge, and artefact publication happen only when the owner asks
for them explicitly, in any session and whatever the sandbox permits. An agent never
publishes on its own initiative, and an earlier instruction to commit does not carry
over to a later change.
