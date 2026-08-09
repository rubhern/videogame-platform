# Backend development

- **Status:** Active walking skeleton with initial persistence
- **Last verified:** 2026-08-09
- **Runtime:** Java 25 without preview features
- **Build:** Repository Maven Wrapper
- **Technology baseline:** [Learning MVP technology baseline](../architecture/technology/mvp-technology-baseline.md)
- **Architecture:** [Learning MVP solution architecture](../architecture/mvp-solution-architecture.md)

The module-local [backend README](../../backend/README.md) is the complete technical
reference for the implementation, runtime configuration, architecture rules, tests,
Actuator API, IntelliJ setup, and troubleshooting. The tracked
[Postman assets](../../backend/postman/) exercise every currently exposed Actuator
operation.

## Supported boundary

The current backend is the smallest executable foundation for the approved modular
monolith. It compiles and starts with Spring Boot 4.1.0, Spring MVC, Actuator, and
Spring Modulith 2.1.0. PostgreSQL 18, SQL-first Flyway migrations, the module-owned
catalogue schema, and deterministic opt-in seed data now provide the initial
persistence boundary. It intentionally implements no product endpoint,
authentication, provider call, catalogue repository, or product behaviour.

Actuator health is the only current HTTP surface. The placeholder packages make
future ownership explicit while keeping domain, application, API, persistence, and
provider types separate before real models are introduced.

## Project layout

The root `pom.xml` is the Maven reactor entry point. The executable application is
the `backend` module:

```text
backend/src/main/java/com/videogameplatform
├── catalogue
│   ├── domain
│   ├── application
│   └── adapter
├── ratings
│   ├── domain
│   ├── application
│   └── adapter
├── identity
│   └── adapter
├── api
│   ├── delivery
│   └── model
└── platform
    ├── configuration
    └── observability
```

Spring Modulith treats the five direct packages as application modules. Catalogue
and Ratings reserve their inward domain and application boundaries. Identity, API,
and Platform are supporting adapter or technical boundaries; they do not contain
business logic.

## Verify

From the repository root, run:

```bash
./mvnw clean verify
```

This is the stable backend verification command. It:

- enforces Java 25 and Maven 3.9 through the backend build;
- compiles with Java release 25 and preview features disabled;
- starts an embedded servlet server and checks `/actuator/health`;
- verifies the five application modules with Spring Modulith;
- applies ArchUnit rules for domain independence, inward application dependencies,
  and separation of API, persistence, and provider models;
- creates and migrates disposable PostgreSQL 18 databases through Testcontainers;
- verifies Flyway checksums, seed determinism, database constraints, and runtime
  privileges;
- creates the executable Spring Boot jar.

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
source .env
set +a
APPLICATION_FLYWAY_ENABLED=true ./mvnw -pl backend spring-boot:run
```

In another terminal, inspect health:

```bash
curl --fail --silent http://localhost:8080/actuator/health
```

The expected response contains `"status":"UP"`. Stop the application with
`Ctrl+C`.

For discovery, aggregate health, liveness, readiness, and info checks, import the
[Actuator Postman collection and local environment](../../backend/postman/README.md).

Spring Modulith also verifies the module arrangement during application startup. A
new invalid module dependency therefore fails both the automated test suite and a
normal local start.

## Package and run the jar

The full verification already creates the executable artifact. Run it directly with:

```bash
java -jar backend/target/videogame-platform-backend-0.1.0-SNAPSHOT.jar
```

This command uses the same Java 25 runtime constraint as the Maven build. Container
packaging and multi-architecture evidence are not part of this backend-only slice.

## Current limitations

The broader walking-skeleton gate remains open. The following evidence is deliberately
not claimed by this skeleton:

- Catalogue JPA entities, repository adapters, publication commands, or public reads;
- application integration with the proven local Keycloak 26.7 realm and server-side
  OIDC session compatibility;
- OpenAPI-backed product delivery;
- CI reproduction and OCI images for `linux/amd64` and `linux/arm64`;
- complete application telemetry export, combined-application resource budgeting,
  and remote deployment.

Add those capabilities only through their focused work items. Do not turn a
placeholder into a product feature without the corresponding approved use case,
contract, tests, and operational evidence.
