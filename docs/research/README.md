# Research and evidence

This directory retains evidence that still explains an approved decision or a
reopening condition. It is historical input, not operational or architectural
guidance, and nothing here is maintained to reflect the current system.

| Artefact | Value retained |
|---|---|
| [Competitor journey comparison](competitor-journey-comparison-metacritic.md) | Evidence behind the differentiation hypothesis |
| [Provider spike](game-data-providers-spike.md) | IGDB/RAWG comparison and release-mode boundary |
| [IGDB PoC result](igdb-poc-results.md) | Measured authenticated technical evidence and limitations |
| [`igdb-poc-sample.csv`](igdb-poc-sample.csv) | Frozen 60-case control sample |
| [Synthetic usability synthesis](simulated-round-synthesis.md) | Prototype record, accepted internal journey decision, and limitations |
| [IGDB PoC tool](../../tools/igdb-poc/README.md) | Reproducible capture/offline-validation procedure |

## Point-in-time reviews

AI-assisted repository reviews at a fixed revision. They are frozen inputs for the
owner's triage, not approved decisions or current state; findings that the owner
accepted became GitHub issues, and anything not turned into an issue was not adopted.
Do not update them when the code changes.

| Review | Revision |
|---|---|
| [Repository quality review](reviews/repository-quality-review-2026-08-27.md) | `9a23801`, 2026-08-27 |
| [MVP close-out: architecture](mvp-closeout-review/architecture-review.md), [code](mvp-closeout-review/code-review.md), [DevOps](mvp-closeout-review/devops-review.md), [infrastructure](mvp-closeout-review/infrastructure-review.md), [observability](mvp-closeout-review/observability-review.md) | `d10bc44`, MVP close-out |

Do not commit personal data, provider secrets, raw authenticated responses, or
material that cannot be stored in the public repository.
