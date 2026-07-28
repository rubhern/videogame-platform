# VideoGame Platform

VideoGame Platform is a product initiative for discovering, tracking, and rating
video games. **Phase 0: Product Brief is complete**, the approved first journey is
captured in the [learning MVP story map](docs/product/mvp-story-map.md), and its
[mobile-first clickable prototype](docs/product/clickable-prototype.md) has completed
an owner-accepted [simulated five-session round](docs/research/simulated-round-synthesis.md).
The focused simulated regression resolved the blocking issue, so the current journey
decision is `PASS` and minimum contracts for one vertical slice can begin.

## Start here

1. Read the [Product Brief](docs/product/product-brief.md).
2. Use the [learning MVP story map](docs/product/mvp-story-map.md) for the current
   journey, release cut, acceptance checks, and deferred scope.
3. Open the [clickable prototype](docs/product/clickable-prototype.md) and use the
   [accepted simulated round](docs/research/simulated-round-synthesis.md) as the
   closed journey decision record.
4. Review the [assumptions](docs/product/assumptions.md).
5. Review the resolved decisions and reopening conditions in
   [open questions](docs/product/open-questions.md).
6. Use the [glossary](docs/product/glossary.md) to keep terminology consistent.
7. Review the [Codex workspace setup](docs/development/codex-setup.md).

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
./mvnw -f tools/igdb-poc/pom.xml clean verify
```

The Maven command requires JDK 21 and tests the isolated IGDB PoC only with
local fixtures. It does not require provider credentials or call IGDB.
