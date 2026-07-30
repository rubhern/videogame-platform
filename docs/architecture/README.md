# Architecture

The initial product problem, journey, MVP boundary, and provider constraints are
explicit. The [prototype journey gate](../research/simulated-round-synthesis.md) is
`PASS`, the [minimum domain model](domain/mvp-domain-model.md) is `Approved`, and the
[minimum application use cases](application/mvp-use-cases.md) and
[MVP solution architecture](mvp-solution-architecture.md) are `Approved`. The
current focus is defining API conventions and the OpenAPI contract for the first
vertical slice.

The approved integration constraints are provider independence, local synchronized
metadata reads, separate release and subscription-availability concepts, no direct
browser calls to the authenticated IGDB API, and attributed direct IGDB CDN cover
references without copied provider image binaries. The initial solution uses one
same-origin web application deployment with a server-side BFF, modular monolith, and
relational data boundary. API Management remains deferred until an adoption trigger
or bounded learning experiment justifies it.

Define only the provider-independent API contract required by one end-to-end
vertical slice. Start with game identity, cover reference and attribution,
platform-region release, date precision and provenance, rating eligibility,
aggregate rating, and the authenticated user's rating. Do not turn the Figma
structure into an application architecture or select a framework, database product,
or hosting platform through the API contract.

## Approved records

- [Learning MVP domain model v1.1](domain/mvp-domain-model.md)
- [Learning MVP use cases and relevant errors v1.0](application/mvp-use-cases.md)
- [Learning MVP solution architecture v1.0](mvp-solution-architecture.md)
- [ADR-0001: Reference IGDB cover images without copying binaries](../decisions/0001-reference-igdb-cover-images.md)
- [ADR-0002: Use a modular monolith and relational data boundary](../decisions/0002-use-a-modular-monolith-and-relational-data-boundary.md)
- [ADR-0003: Use a same-origin BFF and HTTP/JSON API](../decisions/0003-use-a-same-origin-bff-and-http-json-api.md)
- [ADR-0004: Synchronize and serve local catalogue data](../decisions/0004-synchronize-and-serve-local-catalogue-data.md)
