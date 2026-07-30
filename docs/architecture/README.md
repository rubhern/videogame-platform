# Architecture

The initial product problem, journey, MVP boundary, and provider constraints are
explicit. The [prototype journey gate](../research/simulated-round-synthesis.md) is
`PASS`, the [minimum domain model](domain/mvp-domain-model.md) is `Approved`, and the
[minimum application use cases](application/mvp-use-cases.md) are `Approved`.
Minimum API-contract discovery for the first vertical slice can begin from those
contracts. No production framework, database, deployment model, or distributed
architecture is approved yet.

The approved integration constraints are provider independence, local synchronized
metadata reads, separate release and subscription-availability concepts, no direct
browser calls to the authenticated IGDB API, and attributed direct IGDB CDN cover
references without copied provider image binaries.

Define only the provider-independent API contract required by one end-to-end
vertical slice. Start with game identity, cover reference and attribution,
platform-region release, date precision and provenance, rating eligibility,
aggregate rating, and the authenticated user's rating. Do not turn the Figma
structure into an application architecture.

## Approved records

- [Learning MVP domain model v1.1](domain/mvp-domain-model.md)
- [Learning MVP use cases and relevant errors v1.0](application/mvp-use-cases.md)
- [ADR-0001: Reference IGDB cover images without copying binaries](../decisions/0001-reference-igdb-cover-images.md)
