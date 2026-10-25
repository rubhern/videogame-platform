# Architecture decision records

ADRs preserve the reason for significant, durable choices: context, decision,
alternatives, consequences and reconsideration triggers. They do not own current
operating procedures, exact dependency versions or implementation status; those
belong to the linked architecture/development sources and executable manifests.

Create an ADR only when reversing a choice would have meaningful architectural or
product consequences. Supersede accepted records rather than rewriting their
historical decision.

## Accepted

- [ADR-0001: Reference IGDB cover images without copying binaries](0001-reference-igdb-cover-images.md)
- [ADR-0002: Use a modular monolith and relational data boundary](0002-use-a-modular-monolith-and-relational-data-boundary.md)
- [ADR-0003: Use a same-origin BFF and HTTP/JSON API](0003-use-a-same-origin-bff-and-http-json-api.md)
- [ADR-0004: Synchronize and serve local catalogue data](0004-synchronize-and-serve-local-catalogue-data.md)
- [ADR-0006: Use PostgreSQL and versioned forward migrations](0006-use-postgresql-and-versioned-forward-migrations.md)
- [ADR-0007: Use Keycloak as the initial identity provider](0007-use-keycloak-as-the-initial-identity-provider.md)
- [ADR-0008: Use GitHub Actions and GHCR for initial delivery](0008-use-github-actions-and-ghcr-for-initial-delivery.md)
- [ADR-0009: Use OpenTelemetry-compatible instrumentation](0009-use-opentelemetry-compatible-instrumentation.md)
- [ADR-0010: Use Java 25, Spring Boot 4 and Spring Modulith](0010-use-java-25-spring-boot-4-and-spring-modulith.md)
- [ADR-0011: Use Flyway and persistence adapters with PostgreSQL](0011-use-postgresql-and-flyway-for-application-persistence.md)
- [ADR-0012: Use React, TypeScript and Vite for the web frontend](0012-use-react-typescript-and-vite-for-the-web-frontend.md)
- [ADR-0013: Use model-backed and purpose-specific architecture diagrams](0013-use-model-backed-and-purpose-specific-architecture-diagrams.md)
- [ADR-0014: Generate backend HTTP contracts from OpenAPI](0014-generate-backend-http-contracts-from-openapi.md)
- [ADR-0015: Query published release pages with a bounded PostgreSQL read model](0015-query-published-release-pages-with-postgresql.md)
- [ADR-0016: Search the bounded catalogue with PostgreSQL text search](0016-search-the-bounded-catalogue-with-postgresql-text-search.md)
- [ADR-0017: Synchronize relevant catalogue Games and Releases automatically](0017-discover-catalogue-members-automatically-from-igdb.md)
- [ADR-0018: Maintain a Ratings-owned rebuildable game-listing projection](0018-project-personal-ratings-listing-in-ratings.md)
- [ADR-0019: Host private dev on an owner-managed Linux host](0019-host-private-dev-on-an-owner-managed-linux-host.md)
- [ADR-0020: Acquire platform and region taxonomy from accepted releases](0020-acquire-platform-and-region-taxonomy-from-releases.md)
- [ADR-0021: Rank monthly featured releases by locally stored IGDB Hypes](0021-rank-monthly-featured-releases-by-local-igdb-hypes.md)

- [ADR-0022: Localize catalogue content during acquisition](0022-localize-catalogue-content-during-acquisition.md) — private-dev optional activation superseded by ADR-0024.

- [ADR-0023: Automate owner-approved private-dev application promotion](0023-automate-owner-approved-private-dev-application-promotion.md)
- [ADR-0024: Maintain catalogue localization in the private-dev runtime](0024-maintain-catalogue-localization-in-the-private-dev-runtime.md)
- [ADR-0025: Host public production on a deferred single VPS](0025-host-public-production-on-a-deferred-single-vps.md) — #252; single VPS, Docker Compose and static Caddy edge accepted 2026-10-19; Lite 2/former budget input partially superseded by ADR-0033, other rationale/gates preserved; no purchase/exposure authorization.

- [ADR-0026: Defer ARM64 runtime support](0026-defer-arm64-runtime-support.md) — amends ADR-0008/ADR-0019; AMD64-only with a pre-deployment restoration gate.
- [ADR-0027: Revoke and end BFF sessions in the application](0027-revoke-and-end-bff-sessions-in-the-application.md) — #274; direct session invalidation, eight-hour absolute lifetime and OIDC logout; implemented in #274 phase 2 increment 2.
- [ADR-0030: Limit public-edge requests with a Caddy module](0030-limit-public-edge-requests-with-a-caddy-module.md) — #275; one commit-pinned module on the accepted Caddy edge, per-source and aggregate limits keyed on the TCP peer; owner-approved 2026-10-21, implemented locally, real-host evidence in #276.

- [ADR-0033: Select Lite 3 and immutable off-host production storage](0033-select-lite-3-and-immutable-offhost-storage.md) — #276, owner-approved direction 2026-10-25; direct encrypted S3/Compliance uploads to OVHcloud 1-AZ Gravelines, Lenovo optional; contracting/spending and real-host gates unresolved.

## Proposed

- [ADR-0032: Checkpoint historical synchronization by calendar month](0032-checkpoint-historical-synchronization-by-month.md) — #246, owner review pending.

- [ADR-0028: Authorize catalogue operators with a BFF client role](0028-authorize-catalogue-operators-with-a-bff-client-role.md) — #247; a `catalogue-operator` client role in the validated ID token grants catalogue administration reads, the bounded synchronization request (#248) and stopping or abandoning a run (#342); awaiting owner review.
- [ADR-0029: Project community scores into Catalogue for the release order](0029-project-community-scores-for-the-release-order.md) — #285; a Catalogue-owned projection of each rated game's community mean, replaced by Ratings through a Catalogue contract inside every rating write, so the release explorer orders by community score in PostgreSQL before pagination; awaiting owner review.
- [ADR-0031: Count one community vote per rating across platform scores](0031-count-one-community-vote-per-rating-across-platforms.md) — #359; a rating's one contribution is its direct score or the mean of its scored platforms, platform readings are separate per-platform means, and stored contributions keep the community count as players; awaiting owner review.


- [ADR-0021: Collect private-dev logs with Alloy and Loki](0021-collect-private-dev-logs-with-alloy-and-loki.md) — #159 implementation awaiting owner review and host acceptance.

## Superseded

- [ADR-0005: Host private dev on OCI Always Free](0005-host-private-dev-on-oci-always-free.md) — OCI Always Free A1 abandoned and torn down; superseded by ADR-0019.
