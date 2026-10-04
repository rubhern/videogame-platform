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

## Amendment 2026-09-20

Owner decision: the visible attribution and source link are required on the game detail
page, which every displayed cover links to, and no longer on each catalogue release card.
The reference mode, the allowlisted host, the fallback rule and the prohibition on
describing provider artwork as product-owned are unchanged, and the API still delivers the
attribution with every provider cover.

## Amendment 2026-10-03

Owner decision ([#151](https://github.com/rubhern/videogame-platform/issues/151)): featured
discovery may also use IGDB artworks, screenshots and game logos, under the same
`provider_cdn_reference` mode, so that its landscape frames never stretch or crudely crop
a portrait cover:

- persist only the metadata of the hero image, card image and optional secondary-card logo
  selected per game (provider
  image identity, pixel dimensions, transparency, the game's IGDB page, source and
  observation time), never the image binary, and never every candidate;
- construct their URLs from the same template and allowlisted host, with one allowlisted
  size per rendition: `t_1080p` at hero scale and `t_720p` at card or contained scale;
  these fit renditions preserve source proportions for controlled browser cover cropping,
  avoiding a provider crop followed by another browser crop. Use `t_720p` PNG for a secondary-card logo; the fit rendition preserves its
  transparency, including sources rendered as an opaque box by the logo-specific token.
  Other featured images use `webp`;
- keep the attribution rule of the 2026-09-20 amendment: every featured image and logo
  links to the game detail page, which shows the IGDB attribution and source link, and the
  API delivers the attribution with each of them;
- keep the product-owned fallback, now with a landscape variant for featured frames, and
  fall back in the browser whenever an image fails to load. It is the last step everywhere
  except the featured hero, which tries it before its cover and keeps that cover, shown
  whole, only as the last resort.

Covers keep every rule above. Selection is deterministic and uses metadata only; the
featured-media rules are FEAT-003 and FEAT-004 in the
[domain model](../architecture/domain/mvp-domain-model.md).

## Reconsider when

Reopen before a public or commercial release, monetization, binary copying or
proxying, use of another host, or a material provider-terms change. The approved
release mode and provider evidence are recorded in the
[provider spike](../research/game-data-providers-spike.md).
