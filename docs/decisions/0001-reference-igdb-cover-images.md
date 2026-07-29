# ADR-0001: Reference IGDB cover images without copying binaries

- **Status:** Accepted
- **Date:** 2026-07-29
- **Decision owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP

## Context

The approved journey depends on quickly recognizing games. The accepted simulated
usability round found that text-only cards slowed recognition and could make the
product feel unfinished. A catalogue without covers would weaken the intended game
discovery and game-page experience.

IGDB exposes cover metadata through `image_id` and documents an HTTPS image CDN with
fixed size variants. Its documentation also describes local data caching and
commercial attribution expectations. The Twitch Developer Service Agreement places
additional conditions on storage, redistribution, updates, attribution, and the path
back to source material.

The current release mode permits a bounded, private, non-commercial learning product.
It does not approve copying provider image binaries into product-controlled storage,
redistributing them, or assuming that API availability proves ownership of the
underlying artwork.

This ADR is an engineering and product-boundary decision, not legal advice.

## Decision

VideoGame Platform will use approved IGDB covers through a
`provider_cdn_reference` usage mode:

1. The backend provider adapter retrieves and stores normalized cover metadata,
   including the IGDB `image_id`, provider identity, provider update time, and the
   matching IGDB game source reference.
2. The product constructs cover URLs only from the documented
   `https://images.igdb.com/igdb/image/upload/t_{size}/{image_id}.{extension}`
   template.
3. Size, extension, and host values come from application allowlists. Arbitrary
   provider-supplied hosts or executable URL schemes are rejected.
4. The browser loads the cover directly from the IGDB image CDN. Provider
   credentials, tokens, and authenticated API calls never reach the browser.
5. VideoGame Platform does not copy, proxy, commit, persist, redistribute, or expose
   provider image binaries as product-owned assets.
6. Normal browser and provider-CDN delivery caching may occur, but the application
   does not maintain its own persistent binary cache.
7. Every display using an IGDB cover includes visible IGDB attribution and a clear
   link to the matching IGDB game source.
8. The frontend restricts image loading to the allowlisted IGDB CDN and
   product-controlled asset origins through Content Security Policy.
9. A failed, removed, rejected, or no-longer-approved reference immediately uses the
   product-owned fallback without hiding the game.
10. `approved` means approved for this usage mode and release mode. It does not mean
    that VideoGame Platform owns the artwork.

Public deployment, monetization, copied or redistributed images, application-managed
binary storage, a new image host, or a material provider-terms change reopens this
decision before deployment.

## Alternatives considered

### Product-owned fallback for every game

**Pros**

- Lowest provider and rights dependency.
- Fully controlled availability and visual treatment.

**Cons**

- Poorer recognition and weaker visual quality.
- Does not satisfy the desired game-catalogue experience for most titles.

**Decision:** Rejected as the default, retained as the mandatory fallback.

### Copy provider images into product storage

**Pros**

- Full control over delivery, performance, and transformations.
- No runtime dependency on the provider CDN.

**Cons**

- Expands storage, retention, redistribution, deletion, and rights obligations.
- Falls outside the approved private-MVP provider boundary.
- Creates migration work if permission or provider terms change.

**Decision:** Rejected for the current release mode.

### Proxy provider images through the backend

**Pros**

- Hides the provider CDN from the browser.
- Enables centralized caching and transformations.

**Cons**

- Makes the product responsible for binary copying and cache-retention behavior.
- Adds operational complexity without MVP value.
- Can obscure the source and attribution path.

**Decision:** Rejected.

### Use a second provider for images

**Pros**

- Potential fallback if a cover is missing from IGDB.

**Cons**

- Introduces another contract, taxonomy, synchronization path, and rights review.
- The authenticated PoC already found usable IGDB cover references for the bounded
  sample.

**Decision:** Deferred unless IGDB coverage becomes insufficient.

## Consequences

### Positive

- Most supported games can use recognizable covers.
- The product retains a visually rich catalogue without owning or redistributing the
  provider binaries.
- Provider metadata remains isolated behind the adapter.
- Failure behavior is explicit and safe.

### Negative

- Cover delivery depends on IGDB CDN availability and URL behavior.
- The browser contacts a third-party image host.
- Attribution occupies persistent interface space.
- The product cannot perform arbitrary image transformations or guarantee permanent
  cover availability.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Provider image disappears or changes | Track `providerUpdatedAt` and `lastCheckedAt`; use fallback on resolution failure |
| Arbitrary remote-image injection | Construct URLs from allowlisted host, size, extension, and validated `image_id` |
| Credential exposure | Retrieve metadata through the backend; send no provider credentials to the image CDN |
| Attribution omitted in a new view | Make attribution part of the cover presentation contract and acceptance tests |
| Terms or release mode change | Reopen Q-005 and this ADR before deployment |
| Third-party request leaks unnecessary navigation data | Apply an appropriate referrer policy and avoid user identifiers in image URLs |

## Follow-up actions

- Reflect `CoverUsageMode`, attribution, host allowlisting, and fallback in the API
  contract.
- Add adapter contract tests for safe IGDB image URL construction.
- Add frontend tests for fallback, attribution, and Content Security Policy.
- Confirm partnership, storage, image, and attribution requirements before any
  public or commercial deployment.

## Sources

- IGDB API documentation: <https://api-docs.igdb.com/>
- IGDB image reference documentation: <https://api-docs.igdb.com/#images>
- IGDB cover endpoint: <https://api-docs.igdb.com/#cover>
- Twitch Developer Service Agreement:
  <https://www.twitch.tv/p/en/legal/developer-agreement/>
