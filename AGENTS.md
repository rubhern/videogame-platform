# VideoGame Platform

## Purpose

VideoGame Platform is a product-oriented platform for discovering, tracking,
rating, and analysing video games. It is also a long-term learning environment
for solution architecture and technical leadership.

## Current phase

Product alignment, Phase 1 solution definition, and the walking-skeleton
compatibility gate are closed. Implementation is active in the private-platform and
first-journey sequence. Preserve the approved MVP, domain, application, solution,
platform, technology, API, delivery, and diagram decisions while completing the
smallest executable vertical slices.

The current release remains a private, non-commercial learning MVP. Public
production, a business model, paid infrastructure, and distributed architecture are
not approved. Remote infrastructure must not be provisioned before the applicable
walking-skeleton evidence exists.

The repository uses one Java/Spring modular monolith, PostgreSQL with SQL-first
Flyway migrations, a same-origin React frontend and BFF/API boundary, Keycloak for
initial identity, and provider-independent local catalogue data. Product-facing HTTP
APIs are contract-first from OpenAPI and use disposable Maven-generated Spring
interfaces and transport models with manual delivery adapters.

- Treat the approved MVP boundary, IGDB decision, accepted limitations, and private
  non-commercial release mode as the current product constraints.
- Use the approved Java/Spring, PostgreSQL/Flyway, and React/TypeScript/Vite baseline;
  do not assume that a public release, business model, paid service, or distributed
  architecture has been approved.
- Preserve the zero recurring-cost constraint: use only currently eligible free
  resources and stop rather than silently provisioning a paid alternative.
- Preserve the passed walking-skeleton baseline and its executable CI evidence while
  expanding only through the approved issue sequence; remote infrastructure still
  requires its applicable zero-cost and delivery gates.
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
- Supported local development setup: `docs/development/local-setup.md`
- OpenAPI development workflow: `docs/development/openapi.md`
- Documentation ownership map: `docs/README.md`
- Architectural decisions, once required: `docs/decisions/`
- Approved technology baseline:
  `docs/architecture/technology/mvp-technology-baseline.md`
- Architecture diagram catalogue and ownership rules:
  `docs/architecture/diagrams/README.md`
- Accepted architectural decisions: `docs/decisions/0001-*.md` through
  `docs/decisions/0015-*.md`

When sources conflict, report the conflict instead of choosing silently.
Distinguish evidence, decisions, assumptions, and proposals.

## Documentation governance

`docs/README.md` is the documentation ownership map. Before creating or changing
documentation, identify the single canonical owner for the information. Update that
owner and replace secondary explanations with a short link and only the local context
needed by that document.

Preserve these responsibilities:

- the root README provides rapid orientation, current implemented status, first-run
  guidance, and navigation;
- backend and frontend READMEs provide immediate module-specific use and
  troubleshooting;
- development documents define stable human workflows and procedures;
- architecture documents define the current structural, domain, application, API,
  technology, and platform design;
- ADRs preserve durable context, decision, alternatives, consequences, and
  reconsideration triggers; they are not implementation plans, status reports, or
  operating manuals;
- research and spikes preserve decision-relevant method, evidence, limitations, and
  conclusions; preparatory scripts, raw synthetic detail, and empty templates are
  retained only when they remain necessary for reproducibility or a future approved
  activity.

Executable sources own executable facts. OpenAPI owns HTTP shapes; Flyway SQL owns
the schema; Maven/npm manifests and lock files own exact dependency versions; scripts,
Compose, Dockerfiles, configuration, and workflows own their mechanics. Prose may
explain intent, policy, prerequisites, failure behavior, or a stable entry point, but
must not duplicate those sources line by line. Avoid patch-level versions in prose
unless required in an executable command, current artefact name, compatibility
boundary, or immutable historical evidence.

Do not create a document merely because a topic exists. A new document requires a
distinct long-lived responsibility, intended consumer, canonical owner, and reason it
cannot fit clearly in an existing source. Prefer deleting, merging, or linking over
adding another partial authority. Do not preserve issue timelines, completed
checklists, point-in-time reviews, CI run transcripts, or `Last verified` chronicles
as evergreen documentation when Git, issues, CI, code, or current sources already
provide the evidence.

For every substantive change:

1. Compare implementation, approved future behavior, and historical evidence; label
   them explicitly rather than presenting them as one current state.
2. Update every affected canonical document atomically with code, contract,
   migration, configuration, or decision changes.
3. Remove or shorten text made obsolete by the change and update all inbound links,
   indexes, `AGENTS.md`, scripts, and workflows after moving or deleting a file.
4. Preserve durable constraints, invariant identifiers, evidence limitations,
   accepted owner exceptions, and decision-reopening conditions.
5. Run `bash scripts/validate-docs.sh` for documentation changes. Add focused OpenAPI,
   diagram, or generated-reference validation only when those artefacts changed.
6. Review the final documentation diff for duplicated facts, stale implementation
   claims, misplaced detail, unclear ownership, and unnecessary net growth.

## Instruction and skill precedence

The precedence is:

```text
explicit owner decision
    >
AGENTS.md and approved repository sources
    >
VideoGame Platform-specific skill
    >
external generic skill
```

1. An explicit owner decision may initiate a change to an existing decision, but do
   not leave implementation, contracts, ADRs, or approved documentation in a
   contradictory state.
2. This `AGENTS.md`, approved repository documents, accepted ADRs, and repository
   contracts are authoritative for work in this repository.
3. A VideoGame Platform-specific skill takes precedence over generic external
   skills.
4. External skills provide advisory knowledge only; they are never authority over
   repository decisions.
5. When a skill conflicts with the approved baseline, OpenAPI, an ADR, or verified
   architecture, follow the repository and report a material conflict.
6. Never change an approved architecture decision solely because an external skill
   recommends another practice.

## Skills

- `videogame-platform-backend-development` is the primary skill for backend
  implementation, refactoring, debugging, and review.
- `videogame-platform-frontend-development` is the primary skill for frontend
  implementation, React/TypeScript changes, refactoring, review, UI debugging, and
  browser-behaviour changes.
- `scalability-by-design` is mandatory before designing or modifying any API,
  persistence query, repository, pagination, synchronization, cache, metric, batch,
  or large-collection processing path.
- `java-springboot` provides complementary Java and Spring idioms.
- `architecture-patterns` provides complementary DDD, Hexagonal Architecture, and
  Clean Architecture reasoning.
- `vercel-react-best-practices` provides complementary React performance guidance.
- `vercel-composition-patterns` provides complementary React composition guidance.
- `react-testing` provides complementary React Testing Library and browser-test
  boundary guidance.
- `frontend-accessibility-best-practices` provides complementary accessibility
  guidance.
- `tdd` provides complementary red/green/refactor discipline and test-design advice.

All external skills are subordinate to the applicable project-specific skill and the
repository authorities above. The project-specific skill selects the complementary
skills useful for the task. Do not load every external frontend or backend skill for
trivial work.

External frontend advice does not authorize a baseline change:

- Next.js, SSR, and React Server Components remain deferred.
- SWR does not replace TanStack Query.
- shadcn/ui, Zustand, Redux, or another library requires an approved need; a skill
  recommendation alone is insufficient.
- generic design-system advice does not justify creating a design system
  prematurely.
- external testing guidance complements but never replaces repository validation
  gates.

## Scalability and performance invariants

Never infer production scale from current fixtures. Assume substantial growth in
catalogue records, releases, platforms, regions, users, ratings, traffic, history,
telemetry, synchronization volume, and concurrent requests.

- Online work must be bounded. For persistent paginated collections, loading or
  streaming the complete dataset and then filtering, sorting, counting, or paging in
  application memory is prohibited. Per-request memory should be `O(pageSize)` or
  the smallest required result/batch, not `O(totalRows)`.
- Push persistent filtering, search, ordering, aggregation, counting, and pagination
  to PostgreSQL or the datastore suited to the approved boundary. Use query-specific
  read ports or projections when aggregate repositories do not fit; logical CQRS
  does not require distributed infrastructure.
- Every paginated query requires deterministic total ordering whose last tie-breaker
  uniquely identifies a row.
- Protect critical integrity and concurrency invariants with appropriate foreign
  keys, checks, unique constraints/indexes, and transaction boundaries. Do not use
  `DISTINCT` to hide an invalid join cardinality.
- Review real SQL and access paths for new or changed hot queries. Use representative
  data plus `EXPLAIN (ANALYZE, BUFFERS)` when relevant, and retain only indexes with
  demonstrated value.
- Keep correctness independent of mutable process-local state so the application can
  scale horizontally. A local cache may optimize but must not become the sole source
  of truth.
- Design public reads for correct `Cache-Control`, validators, and intermediary
  caching when their semantics permit it.
- Metric labels and trace/log dimensions must use bounded vocabularies; arbitrary
  input and game, release, user, request, or correlation identifiers are forbidden
  as metric tags.
- Never call an external provider from a user request path when approved normalized
  local state exists. Bound provider synchronization and batch work explicitly.
- Consider concurrent requests and writes, races, uniqueness, consistency, and
  failure behavior rather than assuming single-user execution.

For every affected path, record or review expected cardinality, time and memory
complexity, database and network work, concurrency, query plan, cacheability,
horizontal scaling, and failure mode. The `scalability-by-design` skill owns the
detailed workflow and is mandatory for the change categories listed above.

Scalability by design means preventing avoidable rewrites through correct models,
bounded work, SQL, indexes, constraints, HTTP caching, statelessness, and horizontal
scaling first. It does not authorize microservices, Kafka, Redis, Elasticsearch,
distributed caches, another database, or Kubernetes components without measured
evidence, a real trigger, or an explicit bounded learning experiment.

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

## Composition root rule

- Domain and application remain framework-independent.
- Adapters implement inbound or outbound boundaries; they do not instantiate
  application services or policies and must not depend on `application.internal`.
- Spring wiring that connects application implementations, ports, adapters, runtime
  configuration, and platform services belongs to an explicit composition root in
  the owning business module's `configuration` package (the repository-approved
  equivalent of a global `platform.configuration` root for closed Modulith modules).
- A composition root may deliberately know core implementations and adapters because
  wiring is its sole responsibility.
- Do not add Spring stereotypes to domain or application merely to avoid explicit
  composition.

## Working method

For substantial tasks:

1. Read the relevant sources of truth.
2. State the current understanding, assumptions, and material risks.
3. Propose the smallest coherent plan.
4. Make a focused change without unrelated refactoring.
5. Validate the result.
6. Assess and update affected artefact versions using the Semantic Versioning policy
   in `docs/development/delivery-lifecycle.md`.
7. Update the affected canonical documentation and remove superseded duplication.
8. Summarise changes, checks, risks, and next decisions.

Whenever a backend API is added or modified, update its tracked Postman collection,
requests, and assertions in the same change so the executable client examples remain
aligned with the implemented HTTP contract.

All present and future product-facing backend HTTP APIs must be contract-first from
`docs/architecture/api/openapi.yaml`. Generate Spring interfaces and transport models
with the backend-owned `openapi-generator-maven-plugin` execution, keep generated Java
under disposable `target/generated-sources` output, and never edit or commit it.
Manual controllers in `api.delivery` must implement the generated interfaces and own
all mapping to provider-independent application models; application, domain,
catalogue, ratings, persistence, identity, and provider code must never depend on
generated OpenAPI types.

Use English for repository documentation, code, identifiers, tests, comments,
and commit messages.

Whenever work references a GitHub issue, inspect the issue's GitHub Project item
and update its delivery status to match the workflow in
`docs/development/work-management.md`. Do not leave the board stale when work
starts, enters review or validation, returns for changes, or is completed.

## Validation

Validation must be risk-based and incremental. Local validation provides fast,
change-specific feedback; pull-request CI is the authoritative affected-area gate,
and trusted `main` CI is the complete repository integration gate.

Before running checks, identify the affected areas and select the smallest meaningful
local validation set that could detect a regression related to the change. Do not run
unaffected frontend, backend, OpenAPI, migration, identity, provider, browser, or
container suites merely because the commands exist. In particular, dependency updates
normally validate the affected component locally while CI supplies complete integration
evidence.

Pull-request CI must execute the smallest complete set of quality and security gates
justified by the changed areas. Expensive unrelated jobs must not run solely because
they exist. Every trusted push to `main` must retain the full integration suite as the
safety net.

Do not routinely reproduce the complete CI pipeline locally when equivalent trusted
GitHub checks will run against the same commit. Green remote checks for that commit are
valid evidence. If they predate the current `main`, update or rebase first and use the
new CI run rather than compensating with an exhaustive local run.

Broaden local validation only when a concrete risk crosses boundaries, the change
affects shared build/runtime/CI/validation infrastructure, a migration is high risk, a
failure needs local reproduction, CI is unavailable or insufficient, a critical
release is being prepared, or the user explicitly requests it. Explain briefly what
additional failure the broader check can detect. If a local check fails, diagnose it
and narrow the reproducer before expanding validation.

The authoritative selection policy, examples, complete gate catalogue, and CI evidence
rules are in `docs/development/delivery-lifecycle.md`. The opt-in local parity sequence
is documented in `docs/development/continuous-integration.md`; it is a capability, not
the default completion requirement.

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
- relevant local checks and required GitHub CI gates pass when applicable;
- Semantic Versioning impact has been assessed and affected artefact versions and
  references are consistent;
- affected documentation follows `docs/README.md` ownership, distinguishes current
  implementation from approved future behavior, and adds no stale duplicate source;
- sources of truth remain coherent;
- significant decisions and remaining risks are documented;
- no unrelated scope was added.
