---
paths:
  - "backend/src/main/java/com/videogameplatform/*/domain/**"
  - "backend/src/main/java/com/videogameplatform/*/application/**"
  - "backend/src/main/java/com/videogameplatform/*/adapter/**"
  - "backend/src/main/java/com/videogameplatform/*/configuration/**"
  - "backend/src/main/java/com/videogameplatform/api/**"
---

# Backend hexagonal boundaries

Hard constraints for domain, application, and adapter code. The canonical rules and
their rationale are in
[`docs/architecture/mvp-solution-architecture.md`](../../docs/architecture/mvp-solution-architecture.md),
section "Hexagonal dependency rules". `HexagonalArchitectureTest` enforces them.

## Never

- Import `org.springframework..`, `jakarta..`, or `java.sql..` in `domain` or
  `application`. No Spring stereotype belongs there, not even to avoid wiring.
- Import `..adapter..`, `..api..`, or `..platform..` from `domain` or `application`.
- Import `..application..` from `domain`.
- Depend on `..application.internal..` from any adapter or from `api.delivery`.
- Use persistence records, provider DTOs, or HTTP models in `domain`; they stay in
  their own adapter.
- Reference a generated OpenAPI type outside `api.delivery` and `api.generated`, or
  edit generated sources under `backend/target/generated-sources`.
- Reach into another module's repository or tables. Cross-module collaboration goes
  through an explicit application contract.
- Instantiate an application service or policy from an adapter.
- Read the host or client default time zone for a time-dependent product rule. Take
  an application-owned clock and an explicit `Europe/Madrid` evaluation date.

## Always

- Point dependencies inward: `inbound adapter -> application -> domain`, and
  `outbound adapter -> application port -> external technology`.
- Put Spring wiring in the owning module's `configuration` composition root. That
  package may know internal implementations and adapters; nothing else may.
- Keep correctness independent of mutable process-local state.
- Load the `scalability-by-design` skill before changing any API, query, repository,
  pagination, synchronization, cache, metric, batch, or large-collection path, and
  give every paginated query a deterministic total ordering whose last tie-breaker
  uniquely identifies a row.

If a change appears to require breaking one of these rules, stop and report the
conflict instead of relaxing an architecture test.
