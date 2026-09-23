# ADR-0010: Use Java 25, Spring Boot 4 and Spring Modulith

- **Status:** Accepted
- **Date:** 2026-08-04
- **Owner:** Ruben Hernandez
- **Scope:** Initial backend

## Context

The backend needs a current, maintainable JVM baseline that supports transactional
HTTP workloads, security, observability and explicit business modules. The approved
modular monolith does not need a reactive stack or another framework-learning track.

## Decision

- Use Java 25 LTS without preview features, Spring Boot 4.1, Spring MVC and Spring
  Modulith 2.1.
- Use the Maven Wrapper as the supported build entry point and target Java 25 source
  and bytecode in every environment.
- Use Spring Security OAuth2 Client for the BFF and Spring Data only in adapters where
  appropriate.
- Verify module boundaries with Spring Modulith and ArchUnit; neither replaces
  architectural review.
- Keep domain/application free of Spring and infrastructure dependencies. Put
  adapter wiring in explicit module composition roots.
- Keep blocking request/transaction code as the baseline. WebFlux, preview features,
  Lombok and speculative virtual-thread tuning are not approved defaults.

Version families and upgrade policy belong to the
[technology baseline](../architecture/technology/mvp-technology-baseline.md); exact
versions belong to Maven configuration.

## Alternatives considered

- **Java 21/Spring Boot 3:** viable but rejected for a new project after the Java 25
  compatibility gate passed.
- **Java 26/non-LTS:** rejected as the initial long-lived baseline.
- **Another JVM framework:** rejected because it adds learning cost without solving a
  demonstrated product or runtime problem.

## Consequences

The project gains a current LTS runtime, mature Spring ecosystem and executable module
verification. It depends on Java 25 availability, must validate newer ecosystem
compatibility carefully and remains one deployable even with strong logical modules.

## Reconsider when

Revisit for a blocking mandatory-library incompatibility, an approved next-LTS
migration, a measured execution-model need or evidence that Modulith costs exceed its
boundary value.
