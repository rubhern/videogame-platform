---
name: videogame-platform-backend-development
description: Implement, modify, refactor, debug, or review VideoGame Platform backend code using the project's Java 25, Spring Boot, Spring Modulith, DDD, hexagonal architecture, OpenAPI-first, PostgreSQL, Flyway, observability, and testing decisions. Use for backend Java/Spring work. Do not use for frontend-only or product-alignment-only tasks.
---

# VideoGame Platform Backend Development

Implement backend changes according to the repository's approved architecture and contracts.

This skill is project-specific. Repository sources of truth override generic external skills.

## Authority

Before making a material backend change:

1. Read `AGENTS.md`.
2. Identify the owning business module and relevant use case.
3. Read only the approved sources necessary for the change.
4. Read relevant ADRs.
5. Treat `docs/architecture/api/openapi.yaml` as authoritative for product-facing HTTP contracts.
6. Inspect existing implementation and tests before proposing new abstractions.
7. Load `scalability-by-design` for every API, query, repository, persistence,
   pagination, synchronization, cache, metric, batch, concurrency, or
   large-collection change and apply its review before implementation.

When sources conflict, report the conflict instead of choosing silently.

Do not change an approved architecture or product decision merely to follow an external skill.

## Approved backend baseline

Preserve the currently approved baseline unless an explicit owner decision changes it:

- Java 25 LTS.
- Preview Java features disabled.
- Spring Boot 4.1.x.
- Spring MVC.
- Spring Modulith 2.1.x.
- Maven Wrapper as authoritative build entrypoint.
- PostgreSQL 18.x.
- SQL-first Flyway migrations.
- Spring Data JPA/Hibernate selectively inside persistence adapters.
- Testcontainers PostgreSQL for real persistence evidence.
- OpenAPI 3.1.2 as browser-facing contract source of truth.
- OpenAPI Generator Maven plugin for backend HTTP interfaces and transport models.
- Actuator, Micrometer and OpenTelemetry-compatible instrumentation.
- JUnit Jupiter and AssertJ.
- Spring Modulith tests and ArchUnit architecture fitness functions.
- WireMock or equivalent provider fixtures where applicable.

Do not introduce by default:

- Java preview features;
- Lombok;
- H2 as proof of PostgreSQL behavior;
- WebFlux;
- Cucumber;
- microservices;
- message brokers;
- distributed caches;
- search engines;
- additional databases;
- event sourcing;
- distributed CQRS infrastructure.

These require an approved need or bounded experiment.

## Module ownership

The backend is one modular monolith.

Current top-level modules include:

- `catalogue`
- `ratings`
- `identity`
- `api`
- `platform`

Preserve explicit ownership.

Do not create a new module, bounded context, deployable or infrastructure component merely to make code organization look cleaner.

Cross-module collaboration must use approved application/module contracts.

Never bypass a module boundary by querying another module's tables or persistence repositories directly.

## Hexagonal dependency rules

Dependencies point inward.

### Domain

Domain code:

- contains business concepts, policies, invariants, entities and value objects where justified;
- contains no Spring dependencies;
- contains no Jakarta/JPA dependencies;
- contains no OpenAPI-generated types;
- contains no persistence entities;
- contains no IGDB/provider DTOs;
- contains no HTTP concepts;
- contains no telemetry implementation concepts.

Do not create entities, aggregates, value objects, repositories or domain events ceremonially. Introduce tactical DDD patterns only when the domain behavior benefits from them.

### Application

Application code:

- coordinates use cases;
- owns application ports;
- depends on domain concepts;
- remains independent from Spring/Jakarta and concrete adapters according to the current architecture fitness rules;
- does not expose generated OpenAPI models;
- does not depend on JPA entities or provider transport models.

Use ports only at meaningful boundaries. Do not create an interface for every class.

If a transactional requirement cannot be implemented while preserving current application dependency rules, identify the architectural tension rather than bypassing the rules with an arbitrary framework annotation.

### Adapters

Adapters translate between the application/domain and external technology.

Examples:

- HTTP delivery;
- PostgreSQL/JPA;
- IGDB;
- identity;
- telemetry/configuration integration.

Keep technology-specific models at their boundaries.

### Composition root

Spring wiring that connects application implementations, ports, adapters, runtime
configuration, and platform services belongs to an explicit composition root in the
owning business module's `configuration` package. That package is the
repository-approved equivalent of a global `platform.configuration` root for closed
Modulith modules.

A composition root may deliberately know core implementations and adapters, because
wiring is its sole responsibility. Nothing else may. Never add a Spring stereotype to
domain or application merely to avoid writing explicit composition.

## API-first backend development

All product-facing HTTP APIs are contract-first.

For an API addition or change:

1. Start from the approved use case.
2. Inspect/update `docs/architecture/api/openapi.yaml` when the contract changes.
3. Follow `docs/architecture/api/api-conventions.md`.
4. Generate Spring interfaces and transport models with the repository's OpenAPI Generator Maven execution.
5. Keep generated sources under disposable `target/generated-sources`.
6. Never manually edit or commit generated Java.
7. Manual HTTP delivery adapters implement generated interfaces.
8. Map explicitly between generated transport types and provider-independent application/domain models.
9. Never pass generated types into application, domain, persistence, identity or provider code.
10. Update the tracked Postman collection and assertions in the same change.
11. Preserve RFC 9457 Problem Details and stable application error codes.

Do not implement an endpoint first and retrofit OpenAPI afterward.

## Persistence and migrations

PostgreSQL is the persistence authority.

Rules:

- Flyway owns schema evolution.
- Do not use Hibernate schema generation as the maintained schema definition.
- Applied shared-environment migrations are immutable.
- Fix schema history using a new migration.
- Prefer timestamp-based versioned migrations according to repository conventions.
- Keep schemas/tables module-owned.
- Preserve database constraints for critical invariants.
- JPA entities remain persistence-adapter types.
- Do not expose JPA entities to domain/application/API layers.
- Use explicit SQL/projections when clearer or more efficient than forcing entity graphs.
- Detect and test N+1, pagination and fetch behavior where relevant.
- Persistence integration tests use PostgreSQL Testcontainers.
- Never replace PostgreSQL integration evidence with H2.

Use fakes or mocks for application-port tests when appropriate, but real persistence behavior requires real PostgreSQL evidence.

## External provider boundary

IGDB is an external catalogue context.

- Keep it behind provider-independent application ports.
- Never expose IGDB IDs or transport types as product identity.
- Do not make visitor/rating request paths depend on live IGDB.
- Validate and normalize candidate external data before publication.
- Preserve provenance, precision, freshness and explicit unknown/review states.
- Preserve the last valid local state on provider/mapping failure according to approved use cases.
- Use deterministic fixtures for automated tests.
- Do not introduce another provider without an approved trigger.

## Testing strategy

Test behavior at meaningful seams.

Derive seams primarily from approved public boundaries:

- domain policy;
- application use case;
- module contract;
- persistence adapter;
- provider adapter;
- HTTP/OpenAPI boundary;
- BFF/identity boundary;
- end-to-end journey.

Do not require the owner to reconfirm a seam when it is already determined by approved architecture, ports, use cases or contracts.

For behavior changes and bug fixes, prefer:

```text
failing test
-> minimal implementation
-> passing test
-> refactor
```

Do not force ceremonial TDD for documentation, generated output or purely mechanical configuration changes.

### Domain tests

Use fast JUnit/AssertJ tests.

Cover:

- invariants;
- edge cases;
- policy decisions;
- value semantics;
- deterministic time behavior.

### Application tests

Test use-case behavior through application-facing interfaces.

Use explicit fakes or focused mocks for outbound ports.

Do not mock domain objects or implementation internals merely to increase isolation.

### Persistence tests

Use PostgreSQL Testcontainers.

Verify actual:

- mappings;
- constraints;
- transactions;
- queries;
- ordering;
- pagination;
- locking/concurrency where applicable;
- Flyway-created schema.

### Module/architecture tests

Maintain Spring Modulith and ArchUnit fitness functions.

When fixing an architecture violation, prefer an executable architecture test that prevents regression.

### HTTP tests

Verify:

- generated contract implementation;
- status;
- headers;
- caching/preconditions when applicable;
- Problem Details;
- stable error codes;
- mapping;
- authentication/authorization boundaries.

Do not duplicate framework implementation details in tests.

### Provider tests

Use deterministic provider fixtures.

Do not require live provider credentials in CI.

## Test quality

Prefer tests that survive refactoring.

Avoid:

- testing private methods;
- asserting incidental call order;
- excessive `verify(...)`;
- mocking value objects;
- reproducing implementation logic in expected values;
- broad Spring context tests when a focused test proves the behavior;
- unit tests that pretend to prove SQL/database behavior.

Coverage is evidence, not the goal.

Prioritize critical domain policy, contract, authorization, data consistency and failure behavior.

## Spring and Java practices

Use current Java 25 and Spring Boot 4 idioms that are compatible with the approved baseline.

Prefer:

- immutable data where appropriate;
- Java records for suitable boundary/value representations;
- constructor injection;
- explicit dependencies;
- type-safe configuration;
- small cohesive components;
- framework-managed dependency families;
- clear failure semantics.

Do not introduce abstraction solely because a generic Spring best-practice guide recommends it.

Do not add `@Service`, `@Repository`, `@Transactional` or other framework annotations to framework-independent layers if doing so violates current architecture tests.

Inject the repository's trusted `Clock` into time-dependent application behavior instead of calling the system clock directly.

## Observability

Observability is part of behavior, not decoration.

When relevant, preserve:

- W3C trace context;
- correlation;
- bounded metrics;
- safe structured logs;
- health semantics;
- application/business-operation telemetry.

Avoid high-cardinality metric labels.

Do not use:

- user IDs;
- game IDs;
- tokens;
- correlation IDs;
- trace IDs;
- raw URLs;
- arbitrary exception messages

as unbounded metric dimensions.

Never log secrets, credentials, OAuth tokens, cookies, raw provider payloads or unnecessary personal data.

Do not make telemetry or IGDB availability a liveness dependency.

## Security

Treat browser and provider input as untrusted.

Preserve:

- server-derived authenticated identity;
- ownership checks;
- CSRF requirements for cookie-authenticated state changes;
- secure server-side OAuth/OIDC handling;
- validation at boundaries;
- no secrets in Git, URLs, frontend bundles or logs.

Do not weaken security controls to make tests or local development easier.

## Change workflow

For a material backend change:

1. Inspect `git status`.
2. Read the issue/acceptance criteria when available.
3. Determine the owning module.
4. Read the minimum relevant approved sources and ADRs.
5. Inspect existing code and tests.
6. Identify the behavior/architecture seam being changed.
7. Add or adapt the smallest meaningful failing test first when appropriate.
8. Make the smallest coherent implementation.
9. Run focused checks.
10. Refactor only while tests remain green.
11. Run architecture/module checks when the affected dependency boundary requires
    them.
12. Apply the risk-based local validation policy in
    `docs/development/delivery-lifecycle.md`: run the smallest meaningful backend and
    related-boundary checks locally, then use the applicable pull-request gates and
    trusted `main` integration CI. Do not run frontend, container, migration,
    identity, or IGDB suites unless the change or a concrete cross-boundary risk
    affects them.
13. Inspect the entire diff.
14. Update OpenAPI, Postman, migrations, configuration and documentation when affected.
15. Assess versioning impact according to the repository delivery lifecycle.
16. Report changes, evidence, remaining risks and intentionally deferred improvements.

Do not combine unrelated cleanup with the requested change.

## External skills

When installed, these skills may provide complementary guidance:

- `java-springboot`
- `architecture-patterns`
- `tdd`

They are advisory.

This project skill and the repository sources of truth always take precedence.

Examples:

- A generic architecture skill may suggest in-memory adapters for fast tests; this does not replace PostgreSQL Testcontainers for persistence evidence.
- A generic Spring skill may recommend a layering convention; this does not override the project's business-module + hexagonal package boundaries.
- A generic TDD skill may request explicit confirmation of every test seam; approved ports, use cases and contracts are already accepted seams unless genuinely ambiguous.
- A generic framework recommendation never authorizes Spring dependencies in framework-independent domain/application code.

## Review checklist

Before declaring backend work complete, inspect for:

- wrong module ownership;
- dependency-direction violations;
- Spring/JPA/OpenAPI/provider leakage into domain/application;
- unnecessary interfaces or abstractions;
- anemic or ceremonial DDD;
- direct cross-module persistence access;
- API/implementation drift;
- generated-code edits;
- missing Postman updates;
- JPA/entity leakage;
- unsafe migration changes;
- H2 substitution;
- missing PostgreSQL integration evidence;
- weak error/failure semantics;
- overmocked or implementation-coupled tests;
- missing architecture regression tests;
- N+1/query/pagination problems;
- transaction/concurrency problems;
- high-cardinality telemetry;
- sensitive logging;
- hidden provider dependency;
- accidental introduction of distributed complexity;
- unrelated refactoring.

Prefer boring, explicit and maintainable code over clever infrastructure.
