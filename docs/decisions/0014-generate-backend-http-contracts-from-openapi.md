# ADR-0014: Generate backend HTTP contracts from OpenAPI

- **Status:** Accepted
- **Date:** 2026-08-13
- **Owner:** Ruben Hernandez
- **Scope:** Product-facing backend HTTP APIs

## Context

Hand-maintained controller signatures and DTOs duplicate the approved wire contract,
but generated implementations would invite business logic into disposable code. The
hexagonal boundary needs compile-time contract alignment without generated types
leaking inward.

## Decision

- Keep [`openapi.yaml`](../architecture/api/openapi.yaml) as the single wire-contract
  authority.
- Run the pinned OpenAPI Generator Maven plugin during `generate-sources` against the
  complete contract.
- Generate Spring Boot 4/Jakarta/Jackson 3 interfaces and transport models only,
  grouped by coherent tags, below ignored `backend/target/generated-sources/openapi`.
- Implement controllers manually in `api.delivery`; they map generated transport
  models and failures to provider-independent application models.
- Prohibit generated types from domain, application, catalogue, ratings, persistence,
  identity and provider code; enforce the boundary with architecture tests.
- Make the normal backend build regenerate and compile the boundary.
- Update the tracked Postman collection and assertions in the same change as any
  backend HTTP API.

Generation and validation commands belong to the
[OpenAPI development guide](../development/openapi.md).

## Alternatives considered

- **Manual controller signatures/DTOs:** rejected because they create a second
  contract that can drift.
- **Generate controller implementations:** rejected because generated output would
  become an implementation surface.
- **Commit generated Java:** rejected because reproducible disposable output is less
  noisy and cannot become stale in Git.
- **Endpoint fragments:** rejected because they create multiple contract authorities.

## Consequences

Backend signatures compile from the same contract used by frontend generation,
validation and reference docs, while business policy remains hand-written. Clean
builds pay generation cost and generator upgrades require focused compatibility
review.

## Reconsider when

Revisit if generation becomes non-reproducible, the backend leaves Spring MVC or
generated transport types cannot remain confined to delivery.
