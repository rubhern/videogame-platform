# Learning MVP solution architecture

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Release mode:** Private, non-commercial learning MVP

This document owns the current structural architecture. The
[domain model](domain/mvp-domain-model.md), [use cases](application/mvp-use-cases.md),
[OpenAPI](api/openapi.yaml), [platform design](deployment/mvp-platform-and-delivery.md),
and [ADRs](../decisions/README.md) own their more specific contracts.

## Architectural position

```text
Browser
  -> same-origin static frontend + BFF/API + modular monolith
      -> application PostgreSQL

External boundaries:
  Keycloak (authentication)
  IGDB API (bounded synchronization only)
  IGDB image CDN (approved direct cover delivery)
  telemetry backends (optional export)
```

The first deployment is deliberately one application and one relational consistency
boundary. This meets the journey and solo-operability needs without microservices,
broker, distributed cache, search engine, multiple databases, service mesh, or
Kubernetes.

## Context and trust

- Browser input, filters, identifiers, redirects, headers, and rating values are
  untrusted.
- Keycloak authenticates; the BFF validates the protocol and maps validated
  `issuer + subject` to product `UserId`. Product authorization stays in the backend.
- IGDB data is untrusted candidate input and must be normalized and validated before
  publication. Provider identities never become product identity.
- PostgreSQL stores the current product state served to users. IGDB/telemetry outages
  do not make local reads depend on a live fallback.
- Approved covers are constrained direct CDN references under ADR-0001; no provider
  token or image binary enters the browser API or product storage.

## Modules and ownership

| Module/boundary | Owns |
|---|---|
| Catalogue and Releases | Bounded catalogue, games, aliases, covers, commercial releases, taxonomies, provenance/freshness, local reads, and provider synchronization |
| Ratings | Personal rating lifecycle, eligibility, ownership, aggregate policy, and `Mis puntuaciones` |
| Identity | BFF session and external OIDC integration; not product credentials or authorization |
| API | Same-origin HTTP delivery and mapping; no business policy |
| Platform | Runtime composition, configuration, health, correlation, metrics, and tracing |

Ratings obtains release eligibility through a narrow Catalogue application contract.
It never reads Catalogue tables or provider types. Catalogue does not depend on
Ratings. Technical boundaries are not bounded contexts merely because they have a
module.

## Hexagonal dependency rules

```text
inbound adapter -> application -> domain
outbound adapter -> application port -> external technology
```

1. Domain has no Spring, HTTP, persistence, identity, provider, generated OpenAPI, or
   telemetry dependencies.
2. Application coordinates use cases and owns meaningful ports; it remains free of
   generated transport/persistence/provider types.
3. Adapters translate at boundaries and depend inward. They do not instantiate
   application services or depend on `application.internal`.
4. The owning module's `configuration` package is the Spring composition root. It may
   know internal implementations and adapters because wiring is its sole purpose.
5. Cross-module collaboration uses explicit application contracts, never another
   module's repository/table.
6. Persistence records, provider DTOs, and HTTP models stay in their adapters.
7. Time-dependent product rules receive an application-owned clock and explicit
   `Europe/Madrid` evaluation date; the client and host default zone are not trusted.
8. Spring Modulith and ArchUnit tests protect dependency and module ownership.

Tactical DDD is selective: use value objects/entities/policies where behaviour or
invariants justify them; use query-specific read models for reads that do not need
aggregate rehydration. Logical commands, queries, and in-process domain events do not
imply physical CQRS, messaging, or event sourcing.

## Data and consistency

- PostgreSQL is physically shared but tables/schemas have logical module owners.
- Database constraints protect durable uniqueness and referential/concurrency
  invariants.
- Persistent filtering, ordering, aggregation, counting, and pagination remain in
  PostgreSQL with deterministic unique final ordering. Request memory is bounded by
  page/batch size.
- Rating create/update/delete exposes coherent personal and aggregate state; failure
  preserves the previous valid state.
- Synchronization validates before publishing, stages new games, never approves a
  changed cover automatically, and preserves the last valid publication on failure.
- Public reads can use cache validators; session/personal data is never publicly
  cacheable. The application remains correct without process-local state.

## Identity and API boundary

The BFF is a confidential OAuth client using Authorization Code, PKCE, and OIDC. It
keeps tokens server-side and gives the browser an opaque HttpOnly session cookie.
State changes require CSRF proof; redirects are allowlisted; return context is short
lived and replay-safe. Public catalogue and personal resources remain separate.

The product API is contract-first HTTP/JSON. OpenAPI generates frontend types and
backend Spring interfaces/transport models; manual delivery adapters map to
application models. API Management is deferred until multiple external consumers,
independently routed services, centralized policy/quotas, a developer portal, or a
bounded experiment justifies it.

## Quality and failure behaviour

- Health distinguishes liveness/readiness; provider/telemetry outages do not break
  local catalogue readiness.
- Logs, metrics, and traces use stable outcomes and bounded dimensions; secrets,
  personal data, raw inputs, and identifiers are excluded from metric labels.
- Tests cover domain/application rules, module direction, PostgreSQL constraints and
  queries, OpenAPI delivery, identity/CSRF/session behaviour, provider normalization,
  packaged browser journeys, and relevant failure guarantees.
- A provider failure serves the last valid local snapshot; no snapshot returns
  `CATALOGUE_NOT_READY`. Cover failure uses fallback. Failed migration/deployment does
  not activate an incompatible application.

## Evolution rule

Add material technology only when a product use case, measured reliability/scale
limit, valuable independent ownership/deployment boundary, or bounded learning
experiment justifies its cost. Optimize queries/indexes and HTTP/local behaviour
before distributed infrastructure. A new service/store/protocol requires explicit
ownership, consistency, migration, failure, operability, and rollback evidence.

Revisit the modular monolith, page pagination, identity provider, local catalogue,
or hosting only when their documented ADR triggers occur. Directional possibilities
such as messaging, services, specialized stores, advanced analytics, or orchestration
are not an implementation roadmap.
