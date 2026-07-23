# VideoGame Platform

## Purpose

VideoGame Platform is a product-oriented platform for discovering, tracking,
rating, and analysing video games. It is also a long-term learning environment
for solution architecture and technical leadership.

## Current phase

The project is in **Phase 0: Product Brief**.

- Do not assume that the MVP, architecture, technology stack, external provider,
  or business model has been approved.
- Keep proposed product decisions labelled as hypotheses until evidence or an
  explicit owner decision supports them.
- Do not add implementation, infrastructure, or a detailed backlog unless the
  task explicitly advances the project to that phase.

## Sources of truth

- Initial vision: `docs/reference/video-game-platform-vision.pdf`
- Product Brief: `docs/product/product-brief.md`
- Assumptions: `docs/product/assumptions.md`
- Open questions: `docs/product/open-questions.md`
- Glossary: `docs/product/glossary.md`
- Tooling and Codex setup: `docs/development/codex-setup.md`
- Architectural decisions, once required: `docs/decisions/`

When sources conflict, report the conflict instead of choosing silently.
Distinguish evidence, decisions, assumptions, and proposals.

## Product principles

- Resolve a meaningful user problem before designing the technical solution.
- Select one priority user and one primary journey for the MVP.
- Keep the MVP small enough to validate explicit hypotheses.
- Treat external-data licensing as a product and architecture constraint.
- Do not promote long-term vision capabilities into committed scope by default.

## Engineering principles

- Prefer simple, mature, maintainable technology.
- Deliver complete vertical slices when implementation begins.
- Avoid microservices and distributed infrastructure without documented need.
- Keep domain boundaries explicit and external providers isolated.
- Treat testing, security, observability, accessibility, delivery, and
  operations as part of product quality.
- Record significant, durable architecture decisions as ADRs.

## Working method

For substantial tasks:

1. Read the relevant sources of truth.
2. State the current understanding, assumptions, and material risks.
3. Propose the smallest coherent plan.
4. Make a focused change without unrelated refactoring.
5. Validate the result.
6. Update affected documentation.
7. Summarise changes, checks, risks, and next decisions.

Use English for repository documentation, code, identifiers, tests, comments,
and commit messages.

## Validation

Run:

```bash
bash scripts/validate-docs.sh
```

When implementation is added, extend this section with the repository's real
build, test, lint, and security commands.

## Permissions and safety

The project deliberately configures Codex for maximum local autonomy because it
is personal and currently non-critical. Full access does not remove the need to:

- inspect Git status and diffs before staging;
- preserve unrelated work;
- avoid committing secrets or personal data;
- verify destructive targets explicitly;
- prefer recoverable operations;
- validate before committing and pushing.

## Definition of done

A change is complete when:

- the requested outcome and applicable acceptance criteria are satisfied;
- relevant automated checks pass;
- sources of truth remain coherent;
- significant decisions and remaining risks are documented;
- no unrelated scope was added.
