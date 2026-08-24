# Backend development

- **Status:** Active walking skeleton with the first public catalogue read
- **Last verified:** 2026-08-23
- **Runtime:** Java 25 without preview features
- **Build:** Repository Maven Wrapper
- **Technology baseline:** [Learning MVP technology baseline](../architecture/technology/mvp-technology-baseline.md)
- **Architecture:** [Learning MVP solution architecture](../architecture/mvp-solution-architecture.md)

The module-local [backend README](../../backend/README.md) is the complete technical
reference for the implementation, runtime configuration, architecture rules, tests,
Actuator API, IntelliJ setup, and troubleshooting. The tracked
[Postman assets](../../backend/postman/) exercise the implemented product and Actuator
operations.

## Supported boundary

The current backend is the smallest executable foundation for the approved modular
monolith. It compiles and starts with Spring Boot 4.1.0, Spring MVC, Actuator, and
Spring Modulith 2.1.0. PostgreSQL 18, SQL-first Flyway migrations, the module-owned
catalogue schema, deterministic opt-in seed data, and a JDBC read adapter provide the
initial persistence boundary. `GET /api/v1/releases` implements UC-001 from the
current local snapshot with recent/upcoming views, filters, pagination, explicit
quality states, safe covers, public caching, stable errors, and bounded telemetry.
It intentionally makes no request-path provider call.

The release read and Actuator health, info, and metrics form the current HTTP
surface. Explicit probe
groups, build metadata, safe correlation, structured access logs, route-template
metrics, W3C tracing, and optional OTLP export provide the baseline observability
boundary before real product models are introduced.

Every product-facing backend HTTP interface and transport model is now generated
from the reviewed OpenAPI source during Maven `generate-sources`. Manual controllers
implement those interfaces and keep generated types inside delivery. See the
[backend OpenAPI generation standard](backend-openapi-generation.md).

## Project layout

The root `pom.xml` is the Maven reactor entry point. The executable application is
the `backend` module:

```text
backend/src/main/java/com/videogameplatform
├── catalogue
│   ├── domain
│   ├── application
│   ├── adapter
│   └── configuration
├── ratings
│   ├── domain
│   ├── application
│   └── adapter
├── identity
│   └── adapter
├── api
│   └── delivery
└── platform
    ├── configuration
    └── observability
```

Disposable OpenAPI output is created separately under
`backend/target/generated-sources/openapi/java/com/videogameplatform/api/generated`.
It is not source-controlled.

Spring Modulith treats the five direct packages as application modules. Catalogue
and Ratings reserve their inward domain and application boundaries. Identity, API,
and Platform are supporting adapter or technical boundaries; they do not contain
business logic.

`catalogue.configuration` is the module-local Spring composition root. It wires
the framework-independent use-case implementation and policies to the
`ReleaseBrowseReadPort` adapter bean, the shared `Clock`, and validated runtime
properties. Persistence configuration only creates the PostgreSQL port
implementation; adapters and HTTP delivery cannot access `application.internal`.

## Verify

From the repository root, run:

```bash
./mvnw clean verify
```

This is the stable backend verification command. It:

- enforces Java 25 and Maven 3.9 through the backend build;
- enforces the Spotless/google-java-format AOSP baseline and rejects unused or
  wildcard imports;
- compiles with Java release 25 and preview features disabled;
- regenerates all Spring HTTP interfaces and transport models from OpenAPI before
  compilation, so contract/implementation drift fails the build;
- starts an embedded servlet server and checks health groups, version metadata,
  baseline metrics, structured correlation, W3C trace context, and telemetry safety;
- verifies the five application modules with Spring Modulith;
- applies ArchUnit rules for domain independence, inward application dependencies,
  and separation of API, persistence, and provider models;
- creates and migrates disposable PostgreSQL 18 databases through Testcontainers;
- verifies Flyway checksums, seed determinism, database constraints, and runtime
  privileges;
- verifies the release repository and API against PostgreSQL 18, including filters,
  deterministic ordering, date precision, stale data, empty results, local-snapshot
  failure, contract headers, and bounded telemetry;
- generates JaCoCo XML at `backend/target/site/jacoco/jacoco.xml` and a human-readable
  report at `backend/target/site/jacoco/index.html`, without a global coverage
  threshold;
- creates the executable Spring Boot jar.

Run `./mvnw spotless:apply` to repair formatting, review the resulting diff, and then
rerun the complete verification command. The
[continuous-integration guide](continuous-integration.md) explains how SonarQube
Cloud consumes the JaCoCo XML and applies the plan-aware Sonar way gate.

Docker is required for the PostgreSQL 18 Testcontainers evidence. No persistent local
database, identity provider, external service, or provider credential is required.

The repository provides a separately managed
[PostgreSQL and Keycloak topology](local-dependencies.md). The
[database migration guide](database-migrations.md) owns the schema model,
immutability policy, seed boundary, validation command, and local migration startup.
Its linked
[catalogue persistence model](../architecture/diagrams/mermaid/catalogue-persistence-model.mmd)
is the central physical communication view; versioned Flyway SQL remains the
executable schema authority.

## Start locally

Start the dependency topology, load its ignored credentials, and enable Flyway for
the first local startup:

```bash
bash scripts/local-dependencies.sh up
set -a
source backend/.env
set +a
APPLICATION_FLYWAY_ENABLED=true ./mvnw -pl backend spring-boot:run
```

In another terminal, inspect health:

```bash
curl --fail --silent http://localhost:8080/actuator/health
curl --fail --silent http://localhost:8080/actuator/info
curl --fail --silent http://localhost:8080/actuator/metrics
```

The expected response contains `"status":"UP"`. Stop the application with
`Ctrl+C`.

For product and operational checks, import the
[Postman collections and local environment](../../backend/postman/README.md).

The [release API guide](release-api.md) owns its exact query policy, configuration,
snapshot and cover behaviour, HTTP errors, telemetry, and focused test commands.

The [observability guide](observability.md) owns health semantics, source-revision
injection, human and `structured` logging modes, package log levels, correlation and
trace propagation, the current full-trace sampling baseline, metric cardinality,
OTLP settings, sensitive-data rules, and current limitations.

Spring Modulith also verifies the module arrangement during application startup. A
new invalid module dependency therefore fails both the automated test suite and a
normal local start.

## Package and run the jar

The full verification already creates the executable artifact. Run it directly with:

```bash
java -jar backend/target/videogame-platform-backend-0.7.1-SNAPSHOT.jar
```

That default artifact is backend-only. Build the combined browser application with:

```bash
bash scripts/package-application.sh
```

The command creates the Vite production output and invokes Maven's explicit
`with-frontend` profile. The resulting JAR serves only the approved browser entry
routes and leaves `/api`, `/auth`, and `/actuator` under Spring/server ownership.
`bash scripts/validate-browser.sh` starts this exact artifact with a disposable
PostgreSQL 18 database and proves the complete same-origin release read.
`bash scripts/validate-identity.sh` starts it with the `oidc` profile, a fresh
PostgreSQL/Keycloak 26.7 topology, and a real no-retry Chromium login/session/logout
proof. Configuration and security boundaries are in the
[identity guide](identity-bff.md).

Build and verify the production image containing this JAR and its frontend with
`bash scripts/validate-container-image.sh`. The
[container image guide](container-image.md) owns that multi-architecture runtime,
inspection, scan, SBOM, and publication boundary; container concerns do not enter a
domain or application module. Its image-only Maven profile excludes the opt-in
development seed without changing ordinary local packaging or production migrations.

## Current limitations

The walking-skeleton compatibility gate has passed. The following later capabilities
are deliberately not claimed by this backend slice:

- Catalogue write/publication commands, search, and game-detail reads;
- durable product `UserId` mapping and authenticated ratings authorization;
- Remaining OpenAPI operations beyond `GET /api/v1/releases`;
- a deployed collector or OCI telemetry integration and remote deployment.

Add those capabilities only through their focused work items. Do not turn a
placeholder into a product feature without the corresponding approved use case,
contract, tests, and operational evidence.
