# ADR-0030: Limit public-edge requests with a Caddy module

- **Status:** Accepted
- **Date:** 2026-10-21
- **Owner:** Ruben Hernandez
- **Scope:** Per-source and aggregate request limits at the public-production HTTPS edge
- **Relates to:** [ADR-0025](0025-host-public-production-on-a-deferred-single-vps.md), [ADR-0007](0007-use-keycloak-as-the-initial-identity-provider.md) and [ADR-0008](0008-use-github-actions-and-ghcr-for-initial-delivery.md)

## Context

Opening public registration requires bounded signup, authentication, recovery and
action requests at the edge, including direct browser access to Keycloak, with
source identity taken only from the transport peer; the
[R17 conditions](../development/delivery-lifecycle.md#r17-conditional-acceptance-and-opening-conditions)
own that requirement. Catalogue reads and product writes need the same per-source and
aggregate bounds. ADR-0025 accepted one Caddy edge without Docker discovery. Stock
Caddy has no request-rate limiter, and Keycloak has no native per-source limit; its
worker queue and the optional API admission budget shed resource overload only.

## Decision

- Keep Caddy and add one module, [`mholt/caddy-ratelimit`](https://github.com/mholt/caddy-ratelimit),
  approved by the owner on 2026-10-21. It is pinned to one commit, because its only
  tag predates the IPv6-prefix grouping this edge needs.
- Build the edge from the pinned stock Caddy release and builder images. The rebuilt
  binary must contain exactly the stock binary's Go modules plus this one, except an
  explicitly reviewed security override of a stock dependency, which is removed once
  stock Caddy ships the fix. The image is inspected, scanned for image and Go advisories
  and published by digest through the existing trusted-main dependency-image path. It is
  never built on the host.
- Key per-source limits on the TCP peer only: one IPv4 address, or one IPv6 /64.
  Refuse a per-source quota with 429 and Retry-After, and an exhausted aggregate
  budget or maintenance with 503. Keep limiter state in memory, with no distributed
  storage, metrics or logged keys.

## Alternatives

- **Application or Keycloak limits:** rejected. The BFF never sees direct browser
  submissions to Keycloak, and a Keycloak extension is not approved (R17, D24).
- **Another proxy, such as nginx or HAProxy:** rejected. Replacing the accepted edge
  re-opens its TLS, routing and header contract for a capability one module provides.
- **Host firewall connection limits:** rejected. They cannot see HTTPS paths or
  methods, and the identity host needs different budgets for login and recovery.
- **External rate-limit service, Redis or a CDN:** rejected. They add infrastructure
  or a provider without a demonstrated need.

## Consequences

- The edge has a custom image: the recipe, module policy and image checks own its
  inputs, and every Caddy or module update is a reviewed rebuild. Building with the
  current patched Go toolchain also removes standard-library advisories that the stock
  binary carries until its next release.
- The module is not an official Caddy project and has one maintainer. Its source is
  small and auditable, but upstream support is best effort; a dropped compatibility
  or an unfixed defect falls to this repository.
- Limiter state is lost when the edge restarts. That only resets quotas.
- Memory grows with the number of recent sources: every limiter keeps its event
  timestamps until its window passes. Small bursts on long windows and a short sweep
  bound it, and the edge's CPU ceiling limits how fast new sources arrive, but many
  distinct IPv6 /64s can still pressure the memory ceiling; this is distributed abuse,
  which these limits do not prevent.
- The module's own metrics label every client address, so they stay disabled; refusal
  counts must come from bounded edge HTTP metrics instead.
- Docker's userland proxy replaces a client's address with the network gateway. The
  edge refuses such requests; the real host must show that every public client keeps
  its own address before the limits count as evidence.

## Revisit when

- Caddy offers a maintained native limiter, or the module stops following Caddy
  releases or leaves a security defect unfixed.
- Real traffic shows legitimate shared-address users being refused, or edge memory
  near its ceiling.
- Production needs more than one edge, which would need shared limiter state.
