# VideoGame Platform

VideoGame Platform is a product initiative for discovering, tracking, and rating
video games. **Phase 0: Product Brief is complete**. The next work is a small story
map, a lightweight prototype, and the minimum architecture needed for one
end-to-end vertical slice.

## Start here

1. Read the [Product Brief](docs/product/product-brief.md).
2. Review the [assumptions](docs/product/assumptions.md).
3. Review the resolved decisions and reopening conditions in
   [open questions](docs/product/open-questions.md).
4. Use the [glossary](docs/product/glossary.md) to keep terminology consistent.
5. Review the [Codex workspace setup](docs/development/codex-setup.md).

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
