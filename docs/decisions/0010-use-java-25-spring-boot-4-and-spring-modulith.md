# ADR-0010: Use Java 25, Spring Boot 4, and Spring Modulith for the initial backend

- **Status:** Accepted
- **Date:** 2026-08-04
- **Decision owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Technology baseline:** [Learning MVP technology baseline](../architecture/technology/mvp-technology-baseline.md)
- **Solution architecture:** [Learning MVP solution architecture](../architecture/mvp-solution-architecture.md)
- **Platform design:** [Learning MVP platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md)

## 1. Context

The approved architecture requires one deployable modular monolith with explicit DDD
boundaries, hexagonal dependency direction, a same-origin BFF/API, local relational
persistence, backend-only IGDB integration, observability, and automated delivery.
The implementation baseline must select a supported Java platform and Spring stack
without beginning a greenfield project on an unnecessarily old runtime or adopting a
short-lived feature release.

The backend choice must support:

- long-lived maintainability;
- mature HTTP, security, persistence, testing, and observability integrations;
- explicit module verification;
- Java 25 runtime support;
- straightforward container delivery;
- a large professional ecosystem;
- evolution without requiring microservices.

## 2. Decision drivers

- Greenfield implementation with no legacy runtime constraint.
- Current LTS runtime rather than the previous LTS.
- Strong Spring support for Java 25.
- Explicit modular-monolith verification and focused module testing.
- Familiar synchronous transactional programming for PostgreSQL-backed use cases.
- Mature security support for a server-side BFF and OpenID Connect.
- Low operational complexity for one owner.
- No artificial use of reactive programming or preview language features.

## 3. Considered options

### Option A — Java 21 with Spring Boot 3.x

**Benefits**

- Previous LTS with extensive community examples.
- Broad compatibility with mature libraries.
- Lower risk when using older dependencies.

**Costs**

- Starts a greenfield product on the previous LTS.
- Brings an earlier framework generation into a project intended to evolve for years.
- Defers a runtime and framework migration without an existing compatibility reason.

### Option B — Java 25 with Spring Boot 4.1 and Spring Modulith 2.1

**Benefits**

- Java 25 is the current LTS line from most vendors.
- Spring Boot 4.1 officially supports Java 25.
- Spring Modulith 2.1 targets modular, domain-driven Spring Boot applications.
- Provides structural verification, module tests, documentation, and observability
  support without creating distributed services.
- Maximizes useful lifetime before the next required LTS migration.

**Costs**

- Less community content targets this exact version combination than Java 21 and
  Spring Boot 3.
- Some third-party libraries may lag behind the core Spring platform.
- Spring Boot 4 introduces migration work for examples or dependencies written only
  for the previous generation.

### Option C — Java 26 with Spring Boot 4.1

**Benefits**

- Uses the newest feature release.
- Provides early access to the latest finalized Java capabilities.

**Costs**

- Java 26 is not the selected LTS baseline.
- Requires a faster runtime upgrade cadence without product value.
- Increases compatibility churn for a private MVP.

### Option D — Another JVM framework

Examples include Quarkus, Micronaut, or Helidon.

**Benefits**

- Different startup, native-image, and cloud-native trade-offs.
- Valuable alternative learning opportunities.

**Costs**

- The project and owner already have strong Spring experience.
- Replaces product and architecture learning with framework-learning overhead.
- Does not solve a demonstrated runtime, scale, or deployment problem better for the
  current MVP.

## 4. Decision

Use:

```text
Java 25 LTS
Spring Boot 4.1.x
Spring Modulith 2.1.x
Spring MVC
Spring Security OAuth2 Client
Spring Data JPA where appropriate
Maven Wrapper
ArchUnit
```

Additional constraints:

- Product code MUST compile and run without Java preview features.
- Java source and bytecode target are both 25.
- Local, CI, and runtime environments use Java 25.
- Eclipse Temurin is the initial OpenJDK distribution, but domain and application
  code MUST not depend on vendor-specific behaviour.
- Spring Boot and Spring Modulith BOMs manage compatible dependency families.
- Maven Wrapper is the supported build entry point.
- Spring MVC is used initially; WebFlux is not introduced without an end-to-end
  reactive requirement.
- Virtual threads MAY be evaluated after a stable blocking baseline and load test
  exist.
- Lombok is excluded from the baseline.
- Spring Modulith and ArchUnit enforce module and dependency rules; neither tool
  replaces architectural judgement.

## 5. Rationale

Java 25 provides the current LTS foundation appropriate for a new product. The
reduced quantity of community articles targeting Java 25 specifically is not a
blocking constraint because Java language fundamentals remain compatible and the
critical integrations are governed by official Java and Spring documentation.

Spring Boot supplies mature delivery, security, persistence, testing, health, and
observability capabilities. Spring Modulith directly supports the approved strategy:
business modules remain explicit and verifiable inside one deployable, without using
network boundaries as architecture theatre.

Spring MVC fits the current request-response and transactional workload. A reactive
stack would increase programming and debugging complexity without a measured need.

## 6. Consequences

### Positive

- Long-lived LTS runtime for a greenfield product.
- Current Spring generation with official Java 25 compatibility.
- Module boundaries can be tested and documented automatically.
- Familiar and supportable enterprise development model.
- Simple synchronous transactions for ratings and catalogue publication.
- Future extraction remains possible because logical boundaries exist first.

### Negative

- Some libraries, examples, or plugins may require compatibility validation.
- Spring Boot 4 migration knowledge may be less abundant than Spring Boot 3 content.
- Spring Modulith introduces concepts and dependencies that must be used selectively,
  not ceremonially.
- The application is tied to Java 25 runtime availability in every environment.

### Neutral or accepted

- Spring is a framework dependency at adapters and configuration layers; domain code
  remains framework-independent.
- A modular monolith is deployed as one unit even though modules have distinct
  ownership.

## 7. Implementation constraints

1. Run the walking-skeleton compatibility verification before feature expansion.
2. Enforce Java 25 with Maven Enforcer and CI configuration.
3. Disable `--enable-preview` in all maintained build and runtime paths.
4. Verify modules through Spring Modulith and ArchUnit.
5. Keep domain packages free from Spring annotations where practical and from
   infrastructure dependencies entirely.
6. Use application ports between modules and adapters.
7. Use Spring-managed dependencies unless a security or compatibility exception is
   documented.
8. Record source revision and Java runtime in build metadata.
9. Test the packaged image on Java 25, not only the developer JVM.

## 8. Implementation verification

Acceptance authorizes the smallest executable walking skeleton. That implementation
must verify the decision before feature work expands:

- a Java 25 build compiles and tests successfully;
- Spring Boot 4.1 starts without preview flags;
- Spring Modulith verifies at least the Catalogue and Ratings modules;
- a PostgreSQL integration test passes;
- OpenAPI delivery and Keycloak OIDC dependencies are compatible;
- the OCI application image starts and exposes liveness and readiness;
- CI reproduces the same result;
- the released image is built for `linux/amd64` and `linux/arm64` and its ARM64
  variant starts successfully before OCI provisioning.

A blocking incompatibility reopens this ADR. It does not justify silently changing
the approved runtime or omitting the required platform.

## 9. Reconsideration triggers

Revisit this decision when:

- a mandatory library has no viable Java 25 or Spring Boot 4-compatible version;
- official support changes materially;
- the next Java LTS becomes a justified migration target;
- measured workload requires a different execution or concurrency model;
- native-image or startup constraints become material;
- Spring Modulith creates more cost than value after real module implementation.

## 10. Official references

- [JDK 25 project](https://openjdk.org/projects/jdk/25/)
- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Modulith reference](https://docs.spring.io/spring-modulith/reference/index.html)
- [Spring Modulith verification](https://docs.spring.io/spring-modulith/reference/verification.html)
- [Spring Modulith module testing](https://docs.spring.io/spring-modulith/reference/testing.html)

## 11. Change history

| Date | Status | Change |
|---|---|---|
| 2026-08-03 | Proposed | Initial decision selecting Java 25, Spring Boot 4.1, Spring Modulith 2.1, Spring MVC, and Maven. |
| 2026-08-04 | Accepted | Approved the backend baseline and moved executable compatibility evidence to the walking-skeleton gate, including explicit `linux/arm64` verification. |
