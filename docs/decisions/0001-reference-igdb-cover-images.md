# ADR-0001: Reference IGDB cover images without copying binaries

- **Status:** Accepted
- **Date:** 2026-07-29
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP

## Context

Covers materially improve game recognition, but API access does not imply ownership
of the artwork. Copying or proxying provider binaries would create storage,
redistribution and removal obligations outside the approved release mode. This is a
product and engineering boundary, not legal advice.

## Decision

Use approved IGDB covers in `provider_cdn_reference` mode:

- persist normalized provider identity, `image_id`, update time and the matching IGDB
  game reference, never the image binary;
- construct URLs only from IGDB's documented HTTPS template and allowlisted host,
  size and extension values;
- let the browser load the image directly from the IGDB CDN; no provider credential
  reaches the browser;
- show visible attribution and a source link wherever a cover is displayed;
- restrict image origins with Content Security Policy and apply an appropriate
  referrer policy;
- fall back to a product-owned image when a reference is missing, rejected or fails;
- never describe provider artwork as product-owned.

Normal browser/CDN caching is acceptable; an application-managed persistent binary
cache is not.

## Alternatives considered

- **Product-owned fallback for every game:** safe but too weak for recognition;
  retained only as the mandatory fallback.
- **Copy into product storage or proxy through the backend:** rejected because it
  expands rights, retention and operational obligations.
- **Second image provider:** deferred until measured IGDB coverage is insufficient.

## Consequences

The catalogue gains recognizable covers without owning or redistributing binaries,
and provider details remain isolated behind an adapter. Availability still depends
on a third-party CDN, attribution consumes interface space and arbitrary transforms
or permanent availability cannot be guaranteed.

## Reconsider when

Reopen before a public or commercial release, monetization, binary copying or
proxying, use of another host, or a material provider-terms change. The approved
release mode and provider evidence are recorded in the
[provider spike](../research/game-data-providers-spike.md).
