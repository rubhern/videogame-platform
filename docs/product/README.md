# Product documentation

- **Owner:** Ruben Hernandez
- **Last updated:** 2026-08-04

The [Product Brief](product-brief.md) is the approved, closed product alignment
record. The [clickable prototype](clickable-prototype.md) is the accepted interaction
artefact, and the [learning MVP story map](mvp-story-map.md) remains the planning
boundary. Supporting documents keep uncertain information out of the main narrative:

- [Assumptions](assumptions.md): beliefs that still require evidence.
- [Open questions](open-questions.md): resolved decisions and their reopening
  conditions.
- [Glossary](glossary.md): shared and provisional terminology.
- [Prototype usability test guide](../research/prototype-usability-test-guide.md):
  moderated participant script, observation sheet, and decision rule.
- [Accepted simulated round](../research/simulated-round-synthesis.md): five
  synthetic sessions, findings, limitations, and the current journey decision.

## Current status

- The product alignment phase is formally closed for the private, non-commercial
  learning scope.
- Product Brief version `0.9` was approved by Ruben Hernandez on 2026-08-04. It
  preserves the Phase 0 boundary, records the approved technology baseline, and
  closes Phase 1 MVP solution definition without expanding product scope.
- The source vision and research inputs have been reconciled.
- The initial problem, priority user, value proposition, journey, learning-MVP
  boundary, and decision rules are explicit owner decisions.
- All Phase 0 open questions are resolved for the current scope.
- IGDB is approved with limitations for a bounded private learning catalogue after
  the first authenticated PoC. Direct IGDB CDN cover references are allowed with
  attribution, an allowlisted host, and fallback; copied provider image binaries are
  excluded. Public deployment, monetization, application-managed image storage, or
  redistribution must reopen the provider decision.
- The [minimum provider-independent domain model](../architecture/domain/mvp-domain-model.md)
  is approved at version `1.1`.
- The [minimum application use cases and relevant errors](../architecture/application/mvp-use-cases.md)
  are approved at version `1.0`; they complete the application-level basis for the
  provider-independent API contract without selecting implementation technology.
- The [MVP solution architecture](../architecture/mvp-solution-architecture.md) is
  approved at version `1.2`; it selects a same-origin server-side BFF, modular
  monolith, relational data boundary, and local catalogue synchronization while
  deferring API Management and distributed infrastructure until explicit triggers.
- The [REST API conventions](../architecture/api/api-conventions.md) are approved at
  version `1.0`; they fix the initial HTTP resource map, representations, errors,
  BFF session boundary, concurrency controls, compatibility policy, and OpenAPI
  authoring rules.
- The [platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md)
  and [delivery lifecycle](../development/delivery-lifecycle.md) are approved at
  version `1.1`. ADR-0005 through ADR-0009 accept the zero-cost private `dev`,
  PostgreSQL migration, Keycloak, GitHub delivery, and OpenTelemetry boundaries.
- The [technology baseline](../architecture/technology/mvp-technology-baseline.md) is
  approved at version `1.0`; ADR-0010 through ADR-0012 accept the backend,
  persistence-tooling, and frontend choices without duplicating platform ADRs.
- Phase 1 MVP solution definition is complete. Application implementation is active
  at the walking-skeleton gate; remote infrastructure has not started.
- The medium-fidelity mobile-first prototype contains eight transparently curated
  games and 23 states. All eight game pages are navigable, and one representative
  game contains the complete simulated rating journey.
- The accepted simulated round reached 4/5 unaided. A focused simulated regression
  resolved F-01 through F-08 and leaves the journey gate at `PASS`. The simulation
  is decision-grade for this private learning workflow, not evidence from real users
  or evidence of product demand.

This is a personal learning project. Ruben Hernandez owns every product and technical
decision. Any team roles, stakeholder interactions, or delivery ceremonies are
simulated by Ruben with AI assistance; they do not represent additional people or
approval authorities.

## Closed product alignment record

- A primary user segment is selected.
- One initial user problem is stated and supported by evidence or explicitly accepted
  as a testable hypothesis.
- The initial value proposition is coherent with that problem.
- The first user journey and learning-MVP boundary are agreed.
- High-risk assumptions and open questions have an owner and a next action.
- The Product Brief is reviewed and marked `Approved`.

These criteria are complete. Product Brief version 0.3 closed Phase 0 on 2026-07-24,
and version 0.4 recorded the approved prototype behaviour on 2026-07-27 without
expanding the MVP. Version 0.5 records the accepted simulated usability decision on
2026-07-28, version 0.6 records the corrected prototype and focused regression, and
version 0.7 records provider-hosted covers and the approved minimum domain contract.
Version 0.8 reconciles the closed product record with the accepted Phase 1 platform
and delivery decisions without changing the MVP or release mode.
Version 0.9 records the approved technology baseline and Phase 1 solution-definition
closure without treating future implementation checks as completed evidence.
The phase remains closed unless a documented reopening condition is met.

Product-demand assumptions remain accepted risks because no real users were
observed. No additional market study, provider PoC, or public-release legal work is
required for the approved private learning scope. The
[OpenAPI 3.1.2 contract](../architecture/api/openapi.yaml) implements the eight
approved browser operations derived from the domain, application,
solution-architecture, and API-conventions contracts.
