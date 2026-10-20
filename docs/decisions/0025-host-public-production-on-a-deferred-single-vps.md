# ADR-0025: Host public production on a deferred single VPS

- **Status:** Accepted
- **Date:** 2026-10-14; accepted by the owner on 2026-10-19
- **Owner:** Ruben Hernandez
- **Scope:** First public, non-commercial, best-effort release; #252 / #251

## Context

The accepted private MVP and its owner-managed integration host remain valid.
Public release changes cost, exposure, provider rights and personal-data obligations.
The [hosting investigation](../research/public-production-topology.md) compares
the alternatives and records the limits of #85 capacity evidence.

Explicit owner decisions for #252 select netcup VPS Lite 2, x86_64 with 8 GB RAM,
subject to availability, final price and capacity validation; cap **all recurring
costs at EUR 20/month**; choose an open, non-commercial, best-effort launch; and
defer purchase until a production candidate exists. Development continues in local
and CI with temporary private-dev integration. These decisions are effective input
to this decision, not authorization to buy or deploy.

## Decision

Use one separately hosted Linux VPS and Docker Compose for the existing modular
monolith, same-origin frontend/BFF, PostgreSQL and Keycloak. Add one static HTTPS
reverse proxy, Caddy, without Docker discovery/socket access. Keep owner
administration private and make only product and necessary identity routes public.
Reuse current telemetry, secret-file, migration, encrypted-backup and immutable-image
patterns; no managed database, second runtime host, distributed cache, broker,
Kubernetes or public monitoring plane.

The [platform design](../architecture/deployment/mvp-platform-and-delivery.md#public-production-target)
owns the topology, resource envelope and recovery policy. The
[delivery lifecycle](../development/delivery-lifecycle.md#public-production-gates)
owns the separate purchase and exposure gates. No runtime, DNS or service is changed
by accepting a design. Capacity acceptance on the purchased VPS remains mandatory;
an unsuccessful trial does not authorize a larger plan or budget.

Continue local/CI development and use private-dev temporarily as integration,
without promoting its database, realm or secrets into production. After public
acceptance, consider retiring private-dev only once local/CI regression and recovery
evidence make its cost of maintenance unnecessary; retirement is a separate owner
decision with data/evidence preservation.

## Relationship to existing decisions

ADR-0019 still owns private-dev hosting; its private-host boundary is not relaxed.
The owner's public-release budget replaces the former project-wide zero-recurring-cost
restriction for the gated production direction only. This ADR extends
the platform baseline to production; it does not supersede the modular monolith,
PostgreSQL, BFF, Keycloak or local catalogue decisions.

ADR-0001 still governs image-reference mechanics and its historical private-use
approval. Its public-release reconsideration is researched in #252; no broader image
licence, binary copying/proxying or external-score approval follows from this ADR.
Public provider/media acceptance must be recorded under the release gates first.

## Alternatives and consequences

- Coexistence on Lenovo has measured single-runtime performance but no demonstrated
  two-runtime headroom; it couples public compromise, resource pressure, home networking
  and host failure to private-dev. Reject for the first public environment.
- Separate existing owner hardware is not evidenced as available. Buying hardware,
  arranging home ingress and maintaining another physical host adds an unpriced path.
- A single external VPS is the owner-selected option and separates the home integration
  failure/security boundary, while retaining OS, patches, backup and incident duties.
- Managed hosting or a distributed topology adds lifecycle/cost complexity without
  a measured requirement.

Accept one-host downtime and possible deployment interruption. Best effort means no
commercial SLA or staffed support; it does not waive security, privacy, provider
rights or recoverability. Backups must survive host/account loss independently.
The quoted RAM class and Lenovo results are not netcup capacity evidence.

## Reconsider when

Reopen if checkout/renewal exceeds the total budget, the selected plan is unavailable,
the VPS fails the bounded qualification, recovery cannot be demonstrated, provider
terms block the release, abuse exceeds safe limits, or measured storage/query pressure
requires a change. Diagnose bounded SQL, budgets and workload first. Monetization,
HA, paid add-ons or a different provider require explicit decisions; never silently
fall back to the home host or another paid service.
