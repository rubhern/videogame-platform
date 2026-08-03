# VideoGame Platform

VideoGame Platform is a product initiative for discovering, tracking, and rating
video games. **The product alignment phase is closed.** Its approved first journey
is captured in the [learning MVP story map](docs/product/mvp-story-map.md), and its
[mobile-first clickable prototype](docs/product/clickable-prototype.md) completed an
owner-accepted [simulated five-session round](docs/research/simulated-round-synthesis.md).
The focused simulated regression resolved the blocking issue and left the journey
decision at `PASS`.

The [minimum provider-independent domain model](docs/architecture/domain/mvp-domain-model.md),
the [OpenAPI contract](docs/architecture/api/openapi.yaml), and the
[minimum platform and delivery design](docs/architecture/deployment/mvp-platform-and-delivery.md)
for the learning MVP vertical slice are defined. PostgreSQL, Keycloak, GitHub
Actions/GHCR, OpenTelemetry-compatible instrumentation, and a private zero-cost OCI
Always Free `dev` environment are approved through ADR-0005 to ADR-0009. Application
frameworks, public production, and a business model remain unapproved.

**Phase 1 — MVP solution definition is active.** Its next gate is an approved
technology baseline with supported versions and only the ADRs needed for durable
choices. Application implementation starts after that gate with a local walking
skeleton; remote infrastructure follows only after the local topology is proven.

## Start here

1. Read the [Product Brief](docs/product/product-brief.md) as the closed product
   alignment record.
2. Use the [learning MVP story map](docs/product/mvp-story-map.md) for the current
   journey, release cut, acceptance checks, and deferred scope.
3. Open the [clickable prototype](docs/product/clickable-prototype.md) and use the
   [accepted simulated round](docs/research/simulated-round-synthesis.md) as the
   closed journey decision record.
4. Use the [approved domain model](docs/architecture/domain/mvp-domain-model.md) for
   the minimum provider-independent contract.
5. Review the [assumptions](docs/product/assumptions.md).
6. Review the resolved decisions and reopening conditions in
   [open questions](docs/product/open-questions.md).
7. Use the [glossary](docs/product/glossary.md) to keep terminology consistent.
8. Review the [Codex workspace setup](docs/development/codex-setup.md).
9. Use the [OpenAPI validation guide](docs/development/openapi-validation.md) to
   validate the API contract locally and in CI.
10. Browse the [generated API reference](docs/architecture/api/reference/index.html)
    or follow its [regeneration tutorial](docs/development/openapi-web-documentation.md).
11. Use the [platform and delivery design](docs/architecture/deployment/mvp-platform-and-delivery.md)
    and [delivery lifecycle](docs/development/delivery-lifecycle.md) for the walking
    skeleton and private `dev` environment.
12. Review [ADR-0005 through ADR-0009](docs/decisions/) before implementing
    persistence, identity, delivery, hosting, or observability.

## Documentation

- [Product documentation](docs/product/)
- [Original product vision](docs/reference/video-game-platform-vision.pdf)
- [Research](docs/research/)
- [Development environment](docs/development/)
- [Architecture](docs/architecture/)
- [Architecture decisions](docs/decisions/)

Markdown files in this repository are the source of truth. Generated Word or PDF
documents, if needed later, are exports rather than authoritative copies.

## Validation

```bash
bash scripts/validate-docs.sh
npm ci
bash scripts/validate-openapi.sh
bash mvnw -f tools/igdb-poc/pom.xml clean verify
```

OpenAPI validation requires Node.js 22.12 or newer. The Maven command requires
JDK 21 and tests the isolated IGDB PoC only with local fixtures. Neither command
requires provider credentials or calls IGDB.
