# ADR-0014: Generate backend HTTP contracts from OpenAPI

- **Status:** Accepted
- **Date:** 2026-08-13
- **Owner:** Ruben Hernandez
- **Scope:** Product-facing HTTP APIs in the private, non-commercial learning MVP backend
- **API contract:** [Learning MVP OpenAPI](../architecture/api/openapi.yaml)
- **Technology baseline:** [Learning MVP technology baseline](../architecture/technology/mvp-technology-baseline.md)

## Context

The OpenAPI document is already the reviewed source of truth for the same-origin
HTTP API and generates frontend types. The first backend product endpoint initially
used manually maintained Java response records and mapping annotations. Tests could
detect many behavioural differences, but Java compilation did not prove that
controller signatures and transport models still matched the complete contract.

The backend needs one repeatable contract-first approach for implemented and future
operations while preserving the approved hexagonal direction. Generation must not
move HTTP DTOs into application or domain, create business logic, or introduce
editable generated controllers.

## Decision

Use OpenAPI Generator through `openapi-generator-maven-plugin` as the mandatory
backend HTTP contract-generation mechanism:

1. `docs/architecture/api/openapi.yaml` remains the single source contract.
2. The backend Maven module runs the exact pinned generator version during
   `generate-sources` and generates from the complete contract.
3. Use the `spring` generator with interface-only output, no default operation
   implementations, Spring Boot 4, Jakarta, Jackson 3, tag-based focused interfaces,
   and generated transport models.
4. Write output below `backend/target/generated-sources/openapi`; treat it as
   disposable, ignored build output that is never edited or committed.
5. Write real controllers manually in `api.delivery`. They implement generated
   interfaces, map to and from application-owned models, build HTTP responses, and
   translate failures to generated Problem Details models.
6. Prohibit generated OpenAPI dependencies from application, domain, catalogue,
   ratings, persistence, identity, and provider code. Enforce the direction with
   ArchUnit.
7. Make `./mvnw clean verify` regenerate, compile, test, and package the boundary so
   interface or model drift fails the standard build.
8. Update Postman requests and assertions whenever a backend HTTP API changes.

OpenAPI Generator `7.24.0` is the first accepted exact version. Compatibility
mappings and schema expressions required by its beta OpenAPI 3.1 support are
documented in the backend generation guide and remain subordinate to the wire
semantics validated by Redocly and HTTP integration tests.

## Alternatives considered

### Maintain controller annotations and DTOs manually

This keeps the build simpler, but duplicates the contract and allows compile-time
drift. Behavioural tests alone do not prove every signature and transport property.

### Generate complete controller implementations

This reduces manual HTTP code but creates generated implementation surfaces and
encourages business or orchestration logic to leak into disposable output. It also
makes regeneration and review harder.

### Commit generated Java

This avoids generation during some local builds but creates a second source tree that
can become stale, adds noisy diffs, and invites manual edits. Reproducible Maven
generation makes committed Java unnecessary.

### Generate from endpoint-specific contract fragments

This can avoid broad generated interfaces but creates multiple contract authorities.
Tagging the complete authoritative document by coherent delivery adapter preserves a
single source and still produces focused interfaces.

## Consequences

### Positive

- Controller signatures and response DTOs are compiled from the reviewed contract.
- The same full contract drives backend Java, frontend TypeScript, validation, and
  readable API documentation.
- Generated code remains isolated from business policy and provider concerns.
- Future APIs follow one explicit implementation sequence and fail early on drift.

### Negative

- Clean backend builds perform generation and download the pinned plugin on first use.
- OpenAPI 3.1 support is beta and currently requires narrowly documented workarounds
  for JSON-null schemas and polymorphic constants.
- Generated output includes deprecation warnings outside project-owned source.
- Tag changes can affect generated Java interface grouping even when the HTTP wire
  contract is compatible.

## Risks and mitigations

- **Generated-code defects:** compile the complete output, retain Redocly validation
  and HTTP integration tests, and pin upgrades for deliberate review.
- **Boundary leakage:** enforce package direction with ArchUnit and keep all mapping
  in `api.delivery`.
- **Accidental edits or commits:** generate only below ignored `target` output and
  document that failures are fixed in the contract or Maven configuration.
- **Placeholder endpoint exposure:** use focused tags so one manual adapter implements
  one coherent generated interface without unimplemented methods.
- **Tool upgrade drift:** inspect representative interfaces, polymorphic models,
  Jakarta imports, Jackson 3 imports, and null handling before accepting an upgrade.

## Follow-up actions

- Apply the same generated-interface pattern as each remaining OpenAPI operation is
  implemented.
- Remove compatibility workarounds when a reviewed generator upgrade supports the
  OpenAPI 3.1 schemas directly.
- Revisit this ADR if Maven generation becomes non-reproducible, the backend moves
  away from Spring MVC, or generated types cannot remain confined to delivery.
