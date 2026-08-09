# Backend development

- **Status:** Active initial skeleton
- **Last verified:** 2026-08-08
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
Spring Modulith 2.1.0. It intentionally implements no product endpoint, persistence,
authentication, provider call, migration, or product behaviour.

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
- creates the executable Spring Boot jar.

No database, identity provider, external service, provider credential, or Docker
container is required by this verification.

The repository now provides a separately managed
[PostgreSQL and Keycloak topology](local-dependencies.md). It establishes the local
dependency contract but is intentionally not required by the backend skeleton until
the persistence and BFF integration issues add the corresponding application
configuration and tests.

## Start locally

Start the application from the repository root:

```bash
./mvnw -pl backend spring-boot:run
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
java -jar backend/target/videogame-platform-backend-0.0.1-SNAPSHOT.jar
```

This command uses the same Java 25 runtime constraint as the Maven build. Container
packaging and multi-architecture evidence are not part of this backend-only slice.

## Current limitations

The broader walking-skeleton gate remains open. The following evidence is deliberately
not claimed by this skeleton:

- application integration with the proven local PostgreSQL 18 topology, Flyway, JPA,
  and Testcontainers;
- application integration with the proven local Keycloak 26.7 realm and server-side
  OIDC session compatibility;
- OpenAPI-backed product delivery;
- CI reproduction and OCI images for `linux/amd64` and `linux/arm64`;
- complete application telemetry export, combined-application resource budgeting,
  and remote deployment.

Add those capabilities only through their focused work items. Do not turn a
placeholder into a product feature without the corresponding approved use case,
contract, tests, and operational evidence.
