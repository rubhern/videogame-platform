# Learning MVP Technology Baseline

- **Status:** Approved
- **Version:** 1.10
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-08-23
- **Phase:** 1 — MVP solution definition (complete)
- **Implementation evidence:** Complete for the walking-skeleton compatibility gate; private OCI infrastructure and journey expansion remain separate later work
- **Scope:** Private, non-commercial learning MVP
- **Solution architecture:** [Learning MVP solution architecture](../mvp-solution-architecture.md)
- **API conventions:** [Learning MVP API conventions](../api/api-conventions.md)
- **Platform design:** [Learning MVP platform and delivery design](../deployment/mvp-platform-and-delivery.md)
- **Delivery lifecycle:** [Delivery lifecycle](../../development/delivery-lifecycle.md)

> This document records the current, consolidated technology baseline for the first
> executable vertical slice. It states what the project proposes to use, the purpose
> and constraints of each technology, the ADR that owns each significant decision,
> and the policy for evolving the stack without turning the baseline into a list of
> fashionable tools.

> Approval selects the maintained technology combination and closes the solution-
> definition gate. It does not claim that the remote infrastructure already exists.
> The walking skeleton must produce the
> executable compatibility evidence defined in section 16 before feature expansion.

## 1. Purpose

The baseline closes implementation choices intentionally left open by the approved
solution architecture. It provides a coherent starting point for:

```text
release discovery
    -> game page
    -> authentication
    -> personal rating
    -> Mis puntuaciones
```

The selected technologies must support:

- a same-origin browser application and server-side BFF;
- one deployable modular monolith;
- explicit DDD and hexagonal module boundaries;
- API-first delivery through OpenAPI;
- local normalized catalogue reads and bounded IGDB synchronization;
- transactional rating consistency;
- automated tests, builds, migrations, packaging, and deployment;
- useful observability, security, and operability from the first slice;
- progressive evolution without premature distributed infrastructure.

This document is the current stack index. Significant reasoning belongs in ADRs;
exact patch versions belong in executable manifests and lockfiles.

## 2. Decision vocabulary

| Status | Meaning |
|---|---|
| `Inherited` | Already approved by product, application, architecture, API, or platform documentation. |
| `Approved` | Explicitly selected by the owner for implementation. |
| `Deferred` | Deliberately excluded until a documented trigger or bounded experiment exists. |
| `Implementation detail` | May change without an ADR while the approved capability and constraints remain intact. |

`MUST`, `MUST NOT`, `SHOULD`, `SHOULD NOT`, and `MAY` use the same normative meaning
as the API conventions.

## 3. Baseline principles

1. **Use current supported lines for greenfield development.** The project begins on
   Java 25 LTS rather than starting one LTS behind.
2. **Disable preview features in product code.** Experiments MAY use them in isolated
   branches but they do not enter the maintained baseline.
3. **Prefer boring infrastructure and explicit boundaries.** Modern versions do not
   justify unnecessary distributed components.
4. **Use framework-managed dependency families.** Spring Boot and Spring Modulith
   BOMs own compatible dependency versions unless a documented compatibility or
   security reason requires an override.
5. **Pin exact versions in executable files.** Markdown records approved version
   lines; `pom.xml`, lockfiles, container definitions, and workflows record exact
   versions or digests.
6. **Use one real database in development and tests.** H2 or another substitute MUST
   NOT be treated as proof of PostgreSQL behaviour.
7. **Keep API, domain, persistence, and provider models separate.** Generated OpenAPI
   types and JPA entities do not become domain objects.
8. **Instrument through replaceable standards.** Telemetry backends may change
   without changing business behaviour.
9. **Build once and promote unchanged.** The released OCI image is immutable and
   traceable to source.
10. **Introduce technology through need or experiment.** A new component requires a
    product use case, operational evidence, or an explicit learning hypothesis.

## 4. Consolidated baseline

| Area | Approved baseline | Version policy | Decision owner |
|---|---|---|---|
| Java | Eclipse Temurin / compatible OpenJDK | Java 25 LTS | [ADR-0010](../../decisions/0010-use-java-25-spring-boot-4-and-spring-modulith.md) |
| Backend framework | Spring Boot | `4.1.x` | ADR-0010 |
| Modular architecture | Spring Modulith plus ArchUnit | `2.1.x` | ADR-0010 |
| Build | Maven Wrapper | Latest compatible stable Maven line, wrapper-pinned | ADR-0010 |
| HTTP stack | Spring MVC | Managed by Spring Boot | ADR-0010 |
| Security client | Spring Security OAuth2 Client | Managed by Spring Boot | ADR-0010 / [ADR-0007](../../decisions/0007-use-keycloak-as-the-initial-identity-provider.md) |
| Database | PostgreSQL | `18.x` | [ADR-0006](../../decisions/0006-use-postgresql-and-versioned-forward-migrations.md) / ADR-0011 |
| Schema migrations | Flyway Community, SQL-first | Spring Boot-managed compatible line | ADR-0011 |
| Persistence | Spring Data JPA / Hibernate with explicit SQL projections where clearer | Managed by Spring Boot | ADR-0011 |
| Integration database | Testcontainers PostgreSQL | Exact image pinned | ADR-0011 |
| API contract | OpenAPI `3.1.2` | Source-controlled contract | Existing API conventions |
| Contract tooling | Redocly CLI and Redoc CE | Exact npm versions locked | Existing API conventions |
| Backend contract generation | OpenAPI Generator Maven Plugin with Spring interfaces and models | Exact version pinned; currently `7.24.0` | [ADR-0014](../../decisions/0014-generate-backend-http-contracts-from-openapi.md) |
| Frontend contract types | openapi-typescript | `7.x`, exact version locked | ADR-0012 |
| Frontend HTTP client | openapi-fetch behind a product API layer | Current compatible stable line, exact version locked | ADR-0012 |
| Frontend language | TypeScript strict mode | Current stable compatible line | [ADR-0012](../../decisions/0012-use-react-typescript-and-vite-for-the-web-frontend.md) |
| Frontend library | React | `19.2.x` | ADR-0012 |
| Frontend build | Vite | `8.1.x` | ADR-0012 |
| Frontend runtime for build | Node.js | `24` LTS | ADR-0012 |
| Package manager | npm with committed `package-lock.json` | Bundled compatible npm line | ADR-0012 |
| Routing | React Router | Current compatible stable line | ADR-0012 |
| Server state | TanStack Query | Current compatible stable line | ADR-0012 |
| Styling | Tailwind CSS | `4.x` | ADR-0012 |
| Identity provider | Keycloak | `26.7.x` initially | [ADR-0007](../../decisions/0007-use-keycloak-as-the-initial-identity-provider.md) |
| Identity protocol | OpenID Connect, Authorization Code with PKCE | Standards-based | Existing architecture / ADR-0007 |
| Local orchestration | Docker Compose | Current supported Compose specification | Platform design |
| CI/CD | GitHub Actions | Actions pinned to immutable revisions where practical | [ADR-0008](../../decisions/0008-use-github-actions-and-ghcr-for-initial-delivery.md) |
| Container registry | GitHub Container Registry | OCI images by SHA and digest | ADR-0008 |
| Application artefact | One OCI image for frontend assets, BFF/API, and modular monolith | Immutable | Platform design / ADR-0008 |
| Health and metrics | Spring Boot Actuator and Micrometer | Managed by Spring Boot | Baseline-level decision |
| Telemetry | OpenTelemetry-compatible traces, metrics, and logs over OTLP | Compatible stable line | [ADR-0009](../../decisions/0009-use-opentelemetry-compatible-instrumentation.md) |
| Local telemetry backend | Grafana-compatible local stack or equivalent | Replaceable implementation detail | ADR-0009 |
| Infrastructure as code | Terraform for the selected OCI platform | Exact version pinned before provisioning | [ADR-0005](../../decisions/0005-host-private-dev-on-oci-always-free.md) / platform design |

## 5. Backend baseline

### 5.1 Runtime and language

The backend uses Java 25 LTS.

Required constraints:

```text
source level: 25
bytecode target: 25
preview features: disabled
local JDK: Java 25
CI JDK: Java 25
runtime JRE/JDK: Java 25
```

The initial distribution is Eclipse Temurin or another explicitly supported OpenJDK
25 distribution. Local, CI, and container environments SHOULD use the same vendor
family unless a compatibility test proves equivalence.

Java 26 or another non-LTS feature release is not selected for the maintained MVP.
Java 21 remains a fallback only if a blocking ecosystem incompatibility is proven by
an executable spike.

### 5.2 Spring platform

The backend uses:

- Spring Boot `4.1.x`;
- Spring Framework 7 as managed by Spring Boot;
- Spring MVC for request-response delivery;
- Spring Security and OAuth2 Client for the BFF identity boundary;
- Spring Data JPA for transactional persistence adapters;
- Spring Boot Actuator and Micrometer for health and metrics;
- Spring Modulith `2.1.x` for module verification, focused module tests,
  documentation support, and optional module observability;
- ArchUnit for explicit dependency fitness functions not fully expressed through
  Spring Modulith.

The application does not use WebFlux initially. The dominant workloads are
transactional PostgreSQL operations and controlled synchronous HTTP integration,
without a demonstrated end-to-end reactive need.

Virtual threads MAY be evaluated after a stable synchronous baseline exists. They are
not an initial architectural requirement.

### 5.3 Module and dependency rules

The backend remains one deployable modular monolith with at least:

```text
catalogue
ratings
identity adapters
api delivery
platform configuration and observability
```

Required rules:

- domain code depends on no Spring, JPA, OpenAPI, IGDB, identity-provider, or
  telemetry implementation;
- application code coordinates use cases through explicit ports;
- generated API models remain in inbound delivery adapters;
- JPA entities remain in outbound persistence adapters;
- IGDB DTOs remain in the provider adapter;
- cross-module collaboration occurs through application contracts, not direct table
  or repository access;
- module boundaries are verified in automated tests.

### 5.4 Build

Maven Wrapper is the authoritative backend build entry point.

The build SHOULD include:

- Spring Boot dependency management;
- Spring Modulith BOM;
- Maven Enforcer for Java and Maven constraints;
- reproducible build metadata;
- compiler warnings treated deliberately;
- unit, module, integration, architecture, and contract test phases;
- JaCoCo coverage reporting as evidence rather than a blind global target;
- CycloneDX SBOM generation;
- OCI image metadata containing source revision and build provenance.

Lombok is not part of the baseline. Records, constructors, IDE generation, and small
explicit domain types are preferred.

## 6. Persistence baseline

### 6.1 PostgreSQL

PostgreSQL `18.x` is the single application database line for the initial MVP.

It stores:

- internal game and release identity;
- normalized platform-region release tuples;
- catalogue synchronization and data-quality state;
- product user mappings from validated identity subjects;
- personal ratings and their concurrency version;
- aggregate rating state when persisted;
- BFF session or return-context state only if the chosen implementation requires
  database persistence.

One physical database MAY contain module-owned schemas such as:

```text
catalogue
ratings
platform
```

Physical co-location does not permit cross-module table access.

### 6.2 Flyway

Flyway Community is the schema migration mechanism.

The project uses SQL versioned migrations because:

- one database engine is selected;
- reviewers should see the PostgreSQL SQL that will execute;
- forward migration and expand-and-contract fit immutable application delivery;
- Liquibase's richer declarative abstraction, contexts, labels, and rollback model
  are not required by the current scope.

Required migration rules:

1. Migration files are immutable after application to any shared environment.
2. New corrections use a new migration; applied files are not edited.
3. Timestamp-based versions reduce branch collisions.
4. Repeatable migrations are limited to replaceable database objects such as views or
   functions.
5. `clean` is forbidden outside disposable local or CI databases.
6. CI validates migrations and creates a fresh PostgreSQL database from zero.
7. Integration tests run against PostgreSQL using Testcontainers.
8. Remote migrations run as an explicit deployment step before application rollout.
9. The runtime database principal SHOULD not retain schema-changing permissions when
   the platform supports separate migration credentials.
10. Potentially incompatible changes use expand-and-contract across multiple
    releases.
11. Application rollback assumes the migrated schema remains backward compatible.
12. Restore is a recovery mechanism for data loss or corruption, not the default way
    to undo a normal deployment.

Example layout:

```text
backend/src/main/resources/db/migration/
├── V20260803_180000__create_catalogue_schema.sql
├── V20260803_181000__create_ratings_schema.sql
├── V20260805_090000__add_rating_version.sql
└── R__catalogue_search_view.sql
```

### 6.3 Persistence mapping

Spring Data JPA and Hibernate are used selectively:

- domain aggregates are mapped through persistence adapters;
- JPA entities do not leave the adapter;
- database uniqueness and foreign-key constraints protect critical invariants;
- catalogue reads MAY use projections, `JdbcClient`, or explicit SQL where this is
  clearer and more efficient than materializing entity graphs;
- fetch strategies, pagination, and N+1 behaviour are tested;
- schema generation by Hibernate is disabled outside disposable tests; Flyway owns
  the schema.

## 7. API contract baseline

The reviewed source of truth is:

```text
docs/architecture/api/openapi.yaml
```

The contract uses OpenAPI `3.1.2` and the approved API conventions.

Tooling:

- Redocly CLI for linting, bundling, and compatibility checks;
- Redoc CE for static readable documentation;
- OpenAPI Generator Maven Plugin for disposable Spring interfaces and transport
  models compiled before backend implementation;
- openapi-typescript for frontend contract types and openapi-fetch for the thin
  same-origin client;
- contract tests validating implementation and examples;
- generated source treated as disposable output;
- RFC 9457 Problem Details for errors.

Manual delivery adapters MUST implement generated interfaces and map generated
transport models to application-owned contracts. Domain, application, catalogue,
ratings, persistence, identity, and provider code MUST NOT depend on generated
OpenAPI classes. Generated Java remains ignored output under `backend/target` and is
never edited or committed. The detailed mandatory workflow and current OpenAPI 3.1
compatibility mappings are recorded in the
[backend generation standard](../../development/backend-openapi-generation.md).

## 8. Frontend baseline

### 8.1 Core stack

The web application uses:

- Node.js 24 LTS for build and tooling;
- npm with a committed `package-lock.json`;
- TypeScript strict mode;
- React `19.2.x`;
- Vite `8.1.x`;
- React Router for browser navigation;
- TanStack Query for server-state acquisition, caching, invalidation, and mutation
  coordination;
- Tailwind CSS `4.x` for styling;
- openapi-typescript-generated contract types consumed by openapi-fetch behind a
  small product-facing API layer.

The frontend is built as static assets and packaged in the same application OCI image
as the BFF/API and modular monolith.

### 8.2 State policy

- TanStack Query owns remote server state.
- React component state and context own small local UI state.
- A separate global state library is deferred until concrete cross-cutting client
  state becomes difficult to manage.
- Authentication tokens never become frontend state; the browser uses an opaque
  session cookie.
- Personal responses and session state are not persisted in browser storage.

### 8.3 Rendering strategy

The initial application is a client-rendered SPA behind a same-origin server-side
BFF. SSR and React Server Components are deferred because the private MVP has no
validated SEO, public-content latency, or server-rendering requirement.

### 8.4 Frontend testing

- Vitest for unit tests;
- React Testing Library for component behaviour and accessibility-oriented tests;
- Mock Service Worker MAY isolate API scenarios during frontend development;
- Playwright for critical browser journeys;
- generated contract types and the typed fetch client for compile-time integration
  feedback;
- accessibility checks included in component or end-to-end validation.

## 9. Identity baseline

Keycloak `26.7.x` is the initial identity provider for local and persistent `dev`.
It is not automatically the final public-production identity decision.

Required flow:

```text
browser
    -> BFF authorization endpoint
        -> Keycloak OpenID Connect authorization
            -> Authorization Code with PKCE
                -> server-side token exchange
                    -> opaque application session cookie
```

Required controls:

- BFF is a confidential client;
- OAuth access and refresh tokens remain server-side;
- browser session cookie is `Secure`, `HttpOnly`, host-only, and appropriately
  `SameSite` outside local development;
- authenticated state changes require CSRF proof;
- product `UserId` derives only from validated `issuer + subject`;
- mutable claims such as email do not become product identity;
- return context is allowlisted, short-lived, tamper-resistant, and single-use;
- exact Keycloak container version or digest is pinned;
- realm and client configuration is exported or represented reproducibly without
  committed secrets.

## 10. Testing baseline

| Scope | Baseline tools and evidence |
|---|---|
| Domain | JUnit Jupiter and AssertJ; table-driven tests for policies and value objects |
| Application | Focused use-case tests with ports replaced by explicit fakes or mocks |
| Module | Spring Modulith module tests and module verification |
| Architecture | ArchUnit and Spring Modulith structural verification |
| Persistence | Testcontainers PostgreSQL and migration-from-zero validation |
| Provider | WireMock or equivalent fixture server plus IGDB contract fixtures |
| API | OpenAPI conformance, MockMvc integration tests, Problem Details validation |
| Identity/BFF | OIDC integration tests, session, CSRF, expiry, logout, replay safety |
| Frontend | Vitest and React Testing Library |
| End-to-end | Playwright against the packaged application and real local dependencies |
| Performance | k6 or Gatling only after stable critical endpoints exist |
| Recovery | Migration, backup, restore, rollback, and last-valid-snapshot exercises |

Cucumber is not part of the initial baseline. It may be introduced when
business-readable executable specifications are actively maintained by a
cross-functional group, not merely to duplicate existing acceptance tests.

## 11. Observability baseline

The application uses:

- Spring Boot Actuator for liveness, readiness, health, and selected operational
  information;
- Micrometer for application and runtime metrics;
- OpenTelemetry-compatible instrumentation for traces, metrics, and logs;
- OTLP as the preferred export protocol;
- W3C Trace Context propagation;
- structured JSON logs outside local interactive development;
- `X-Correlation-ID` at the HTTP boundary, correlated with trace context;
- bounded cardinality in metric dimensions;
- no tokens, cookies, raw identity subjects, credentials, or provider payloads in
  telemetry.

Minimum observable areas:

```text
HTTP requests
application use cases
module collaboration where useful
database pool and query duration
identity outcomes
rating commands and rejection reasons
catalogue synchronization runs
snapshot freshness
deployment version and health
```

The initial storage and visualization backend is replaceable. A separate ADR is
required only when the project selects a durable remote telemetry service with cost,
retention, or operational consequences.

## 12. Code quality and software supply chain

Approved controls for incremental adoption and learning:

| Control | Tool or mechanism |
|---|---|
| Java formatting | Spotless |
| Build constraints | Maven Enforcer |
| Test coverage evidence | JaCoCo |
| Code quality | SonarQube Cloud with explicit gate rules |
| SAST | GitHub CodeQL |
| Dependency updates | Dependabot |
| Secret scanning | GitHub secret scanning plus Gitleaks in CI |
| Container scanning | Trivy |
| SBOM | CycloneDX |
| Workflow hardening | Minimal permissions and third-party actions pinned to immutable revisions |
| Dependency provenance | Lockfiles, Maven checksums, and repository controls where supported |

Coverage is not governed by a single arbitrary global percentage. Gates focus on
critical domain policies, contracts, authorization, persistence, failure behaviour,
and the primary user journey.

Issue #24 implements Spotless, JaCoCo XML/HTML, a plan-aware SonarQube Cloud Sonar way
gate, CodeQL, Dependabot, Maven dependency submission, Gitleaks, actionlint,
changed-range whitespace checks and workflow hardening alongside compiler lint,
Maven Enforcer, ESLint, Spring Modulith and ArchUnit. The
[continuous-integration guide](../../development/continuous-integration.md) records
the exact jobs, permissions, caches, report paths, plan behaviour, failure policy and
deliberately deferred image controls.

## 13. Build, packaging, and delivery baseline

The repository uses GitHub Actions and GitHub Container Registry.

Delivery rules:

1. Pull requests run documentation, OpenAPI, backend, frontend, architecture,
   integration, security, and packaging validations appropriate to the change.
2. Merge to `main` produces one immutable OCI application image.
3. The image contains compatible frontend assets and backend code built from the same
   source revision.
4. PostgreSQL and Keycloak remain external dependencies.
5. The image is tagged by commit SHA and identified authoritatively by digest.
6. The same digest is promoted; environments do not rebuild source.
7. Images run as a non-root user with a read-only filesystem where practical.
8. Build secrets do not enter image layers.
9. GitHub Environments hold environment-specific secrets and approvals.
10. Future cloud access SHOULD use workload identity or OIDC rather than long-lived
    cloud credentials.
11. Deployment records capture source commit, image digest, migration version,
    environment, initiator, outcome, and smoke-test result.

## 14. Local development baseline

The supported workstation is Windows with Ubuntu on WSL2 and the repository stored
in the Linux filesystem.

A developer SHOULD be able to execute:

```text
clone repository
validate prerequisites
copy non-secret local configuration template
start PostgreSQL and Keycloak with Docker Compose
run Flyway migrations
start backend and frontend development processes
execute the full verification suite
build the production OCI image
run smoke tests
```

Docker Desktop integration with the Ubuntu WSL distribution must be enabled before
container-dependent development becomes mandatory.

Local commands become repository scripts or documented Maven/npm commands once they
stabilize. IDE-specific behaviour is never the only supported path.

## 15. Versioning and upgrade policy

### 15.1 Markdown versus executable versions

The baseline records version lines:

```text
Java 25
Spring Boot 4.1.x
Spring Modulith 2.1.x
PostgreSQL 18.x
React 19.2.x
Vite 8.1.x
Node.js 24 LTS
Keycloak 26.7.x
```

Exact versions are pinned in:

```text
.mvn/wrapper/
pom.xml
package.json
package-lock.json
Dockerfile
compose.yaml
GitHub Actions workflows
```

### 15.2 Upgrade classes

| Upgrade | Default treatment |
|---|---|
| Security patch | Prioritized PR, full relevant validation, expedited merge when risk warrants |
| Normal patch | Automated or scheduled PR with full CI |
| Minor version | Reviewed PR, compatibility notes, full CI and smoke tests |
| Major version | Baseline review, compatibility spike, migration plan, and ADR update or replacement |
| JDK LTS change | Dedicated ADR review and full platform compatibility test |
| Database major | Backup/restore rehearsal, data migration plan, performance check, and ADR review |
| Identity major | Realm/client export test, login journey validation, and rollback plan |

No production or shared environment uses floating `latest` tags.

## 16. Walking-skeleton compatibility gate

Baseline approval authorizes creation of the smallest executable walking skeleton.
Before feature work expands beyond that skeleton, it must prove that the central
combination works together:

```text
Java 25
Spring Boot 4.1.x
Spring Modulith 2.1.x
PostgreSQL 18.x
Flyway
Spring Data JPA
Testcontainers
openapi-typescript and openapi-fetch
Keycloak 26.7.x
React 19.2.x
Vite 8.1.x
```

Minimum acceptance criteria:

- backend compiles and starts on Java 25;
- preview features are not required;
- Spring Modulith verifies the intended module structure;
- Flyway creates a new PostgreSQL 18 database from zero;
- a persistence integration test passes through Testcontainers;
- a public endpoint conforms to an OpenAPI fragment;
- Maven regenerates and compiles every backend Spring interface and transport model
  from the complete reviewed OpenAPI source;
- openapi-typescript generates types from the complete reviewed OpenAPI 3.1.2 source,
  including its `oneOf` schemas, and the generated types pass `tsc --noEmit`;
- openapi-fetch builds and calls the same-origin API through the product-facing layer;
- OIDC login can establish a BFF session in local integration;
- the complete application image builds for both `linux/amd64` and `linux/arm64`;
- the published or locally inspected OCI manifest contains both platforms;
- the `linux/arm64` application image starts and passes liveness/readiness on native
  ARM64 or in an explicitly recorded emulated CI test;
- the selected PostgreSQL 18 and Keycloak 26.7 images expose `linux/arm64` manifests
  and start with the local topology;
- the full topology stays within an initial explicit CPU and memory budget compatible
  with the OCI Ampere A1 limit of 2 OCPU and 12 GB;
- liveness, readiness, structured logs, metrics, and trace correlation are visible;
- CI runs the proof reproducibly.

As of 2026-08-23, the PostgreSQL/Flyway, generated backend/frontend contracts, first
public read, minimal accessible releases shell, combined JAR packaging, baseline
observability, and current CI portions are executable. The production
migration creates a fresh PostgreSQL 18
schema and Testcontainers verifies migration and persistence constraints. Explicit
health groups, safe build/source metadata, ECS request correlation, route-template
metrics, W3C trace context, and optional OTLP trace/metric export are covered by an
automated PostgreSQL-backed smoke test. Pull requests and trusted `main` builds run
the same documentation, OpenAPI, generated-type, frontend, browser, backend,
architecture, migration and fixture checks plus Gitleaks, dependency review and
CodeQL. Chromium now proves the packaged browser assets call the real same-origin
release API backed by a fresh PostgreSQL 18 database, with typed date unions,
keyboard navigation, and axe-core evidence. A separate real-browser gate now proves
Keycloak 26.7 Authorization Code/OIDC with PKCE, server-side token exchange and
validation, an opaque HttpOnly application session, minimal no-store session state,
CSRF/origin/fetch-metadata protection, and logout without protocol mocks or retries.
Buildx now produces one OCI index containing `linux/amd64` and `linux/arm64` images;
both variants start as non-root, pass liveness/readiness and same-origin boundary
checks, expose matching source metadata, pass the unsuppressed Trivy severity gate,
and have retained CycloneDX SBOMs. CI defines explicit ARM64 QEMU evidence and copies
the exact validated index to GHCR by commit SHA with its digest preserved. This is
complete compatibility evidence: the full application/PostgreSQL/Keycloak Compose
profile has executable resource-limit validation at 2 OCPU and 2.5 GiB, both exact
dependency images expose AMD64 and ARM64 manifests, and trusted `main` run
`32661542668` passed the complete hosted suite and published the inspected index for
commit `e138f54`. The concise commands and immutable digest are recorded in the
[walking-skeleton evidence](../../development/walking-skeleton-evidence.md). Remote
OCI provisioning, telemetry integration, deployment, recovery and the later product
journey remain separate gates and are not implied by this PASS.

A failure blocks feature expansion and reopens the affected ADR or baseline row. It
does not automatically force Java 21 or permit dropping ARM64. First identify whether
the problem is configuration, emulation, a patch-level incompatibility, an image
manifest, or an optional library. A baseline fallback requires recorded evidence and
owner approval.

## 17. Explicitly deferred technologies

```text
Kubernetes
API Manager or dedicated gateway
Kafka or another message broker
Redis or another distributed cache
Elasticsearch or another search engine
gRPC
service mesh
microservices
physical CQRS with separate stores
event sourcing
multiple database engines
multi-region deployment
external feature-flag platform
public mobile applications
```

Adoption requires a documented trigger, an ADR or bounded experiment, baseline and
success metrics, operational consequences, and a retention or removal decision.

## 18. Significant decision map

| Decision | ADR | Status |
|---|---|---|
| Java 25, Spring Boot 4.1, Spring Modulith 2.1, Maven, MVC | [ADR-0010](../../decisions/0010-use-java-25-spring-boot-4-and-spring-modulith.md) | Accepted |
| PostgreSQL and forward migrations | [ADR-0006](../../decisions/0006-use-postgresql-and-versioned-forward-migrations.md) | Accepted, inherited |
| PostgreSQL 18, Flyway, SQL-first migrations, JPA adapter strategy | [ADR-0011](../../decisions/0011-use-postgresql-and-flyway-for-application-persistence.md) | Accepted |
| React 19.2, TypeScript, Vite 8.1, Node 24 LTS, frontend testing | [ADR-0012](../../decisions/0012-use-react-typescript-and-vite-for-the-web-frontend.md) | Accepted |
| Keycloak 26.7 for local and `dev` identity | [ADR-0007](../../decisions/0007-use-keycloak-as-the-initial-identity-provider.md) | Accepted, inherited |
| GitHub Actions, GHCR, immutable OCI delivery | [ADR-0008](../../decisions/0008-use-github-actions-and-ghcr-for-initial-delivery.md) | Accepted, inherited |
| OpenTelemetry-compatible instrumentation | [ADR-0009](../../decisions/0009-use-opentelemetry-compatible-instrumentation.md) | Accepted, inherited |

No separate ADR is required yet for individual test libraries, formatting tools, or
the replaceable local telemetry backend.

## 19. Official references

- [JDK 25 project](https://openjdk.org/projects/jdk/25/)
- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Modulith reference](https://docs.spring.io/spring-modulith/reference/index.html)
- [PostgreSQL 18 documentation](https://www.postgresql.org/docs/18/)
- [Flyway commands](https://documentation.red-gate.com/flyway/reference/commands)
- [Flyway PostgreSQL support](https://documentation.red-gate.com/fd/postgresql-database-277579325.html)
- [Liquibase changelog concepts](https://docs.liquibase.com/secure/user-guide-5-2-1/what-is-a-changelog)
- [Node.js release schedule](https://nodejs.org/en/about/previous-releases)
- [React versions](https://react.dev/versions)
- [Vite 8.1 announcement](https://vite.dev/blog/announcing-vite8-1)
- [OpenAPI TypeScript](https://openapi-ts.dev/)
- [openapi-fetch](https://openapi-ts.dev/openapi-fetch/)
- [Keycloak 26.7 release](https://www.keycloak.org/2026/07/keycloak-2670-released)
- [Keycloak supported databases](https://www.keycloak.org/server/db)
- [OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/)
- [GitHub Container Registry](https://docs.github.com/packages/working-with-a-github-packages-registry/working-with-the-container-registry)

## 20. Approval checklist

- [x] Java 25 LTS and disabled preview features are accepted.
- [x] Spring Boot 4.1 and Spring Modulith 2.1 are accepted.
- [x] Spring MVC is accepted over initial WebFlux.
- [x] Maven Wrapper is accepted as the backend build entry point.
- [x] PostgreSQL 18 is accepted as the application and Keycloak database server line.
- [x] Flyway Community and SQL-first forward migrations are accepted.
- [x] JPA is accepted as an adapter technology, not the domain model.
- [x] React 19.2, TypeScript strict mode, Vite 8.1, Node 24 LTS, and npm are accepted.
- [x] TanStack Query is accepted for server state; another global state store remains deferred.
- [x] openapi-typescript and openapi-fetch are accepted for the OpenAPI 3.1.2 frontend boundary.
- [x] OpenAPI Generator Maven Plugin is accepted for disposable backend Spring
      interfaces and transport models with manual delivery adapters.
- [x] Keycloak 26.7 is accepted for local and `dev` identity.
- [x] GitHub Actions and GHCR remain accepted for initial delivery.
- [x] OpenTelemetry-compatible instrumentation remains accepted.
- [x] The broad quality and supply-chain toolset is retained for deliberate learning.
- [x] Exact versions will be pinned in executable manifests and not duplicated as patch-level policy here.
- [x] Local, CI, `linux/amd64`, and explicit `linux/arm64` evidence are mandatory in the walking-skeleton gate.
- [x] Deferred technologies remain outside the MVP unless a trigger or experiment is approved.

## 21. Change history

| Date | Version | Change | Owner |
|---|---|---|---|
| 2026-08-23 | 1.10 | Closed the walking-skeleton compatibility gate with executable dependency-manifest and 2 OCPU/12 GB resource-budget checks, complete trusted-main CI evidence, and an immutable published multi-architecture digest without changing the approved baseline. | Ruben Hernandez |
| 2026-08-23 | 1.9 | Recorded the non-root multi-architecture application image, ARM64 runtime probes, manifest/source metadata, Trivy/CycloneDX evidence, digest-preserving trusted-main GHCR publication workflow, and compatible Log4j/Jackson security patches identified by the image scan. | Ruben Hernandez |
| 2026-08-22 | 1.8 | Recorded the real Keycloak 26.7 browser/BFF Authorization Code with PKCE, opaque session and CSRF/logout local/CI compatibility evidence without selecting a distributed session store. | Ruben Hernandez |
| 2026-08-22 | 1.7 | Recorded the typed accessible releases shell, reproducible combined Spring Boot JAR, and real browser-to-PostgreSQL same-origin smoke as partial compatibility evidence. | Ruben Hernandez |
| 2026-08-13 | 1.6 | Adopted OpenAPI Generator Maven Plugin 7.24.0 as the mandatory backend HTTP interface/model generation standard and recorded the enforced delivery-only dependency boundary. | Ruben Hernandez |
| 2026-08-13 | 1.5 | Recorded the Java/Spring/JDBC implementation of the reviewed PostgreSQL-backed release API with PostgreSQL 18 repository/API integration evidence and no request-path provider call. | Ruben Hernandez |
| 2026-08-13 | 1.4 | Recorded reproducible PR/`main` quality and security gates with Java 25, Node.js 24, PostgreSQL 18, Spotless, JaCoCo, plan-aware SonarQube Cloud, dependency submission, pinned actions, minimal permissions, dependency-only caches, and no test retries. | Ruben Hernandez |
| 2026-08-09 | 1.3 | Recorded executable health, version metadata, structured correlation, bounded metrics, W3C tracing, telemetry-safety, and optional OTLP export evidence without claiming a deployed collector. | Ruben Hernandez |
| 2026-08-09 | 1.2 | Recorded executable PostgreSQL 18 migration-from-zero, deterministic seed, persistence-constraint, runtime-privilege, and Flyway checksum evidence without closing the broader compatibility gate. | Ruben Hernandez |
| 2026-08-03 | 0.1 | Initial proposed baseline covering runtime, backend, persistence, API, frontend, identity, testing, observability, quality, and delivery. | Ruben Hernandez |
| 2026-08-04 | 1.0 | Approved the coherent technology baseline, inherited existing platform ADRs without duplication, and made local, CI, AMD64, and ARM64 proof an explicit walking-skeleton gate. | Ruben Hernandez |
| 2026-08-08 | 1.1 | Recorded partial executable evidence for the backend/frontend foundations and local PostgreSQL/Keycloak topology without closing the compatibility gate. | Ruben Hernandez |
