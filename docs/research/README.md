# Research and evidence

This directory retains evidence that still explains an approved decision or a
reopening condition. It is historical input, not operational or architectural
guidance, and nothing here is maintained to reflect the current system.

| Artefact | Value retained |
|---|---|
| [Competitor journey comparison](competitor-journey-comparison-metacritic.md) | Evidence behind the differentiation hypothesis |
| [Provider spike](game-data-providers-spike.md) | IGDB/RAWG comparison and release-mode boundary |
| [IGDB PoC result](igdb-poc-results.md) | Measured authenticated technical evidence and limitations |
| [External quality signals](external-quality-signals.md) | #256 authenticated score coverage, provider-rights limits and owner-approved source decisions for #255, plus the #255 phase-1 actual-catalogue coverage measurement; presentation and public/commercial authorization remain pending |
| [`igdb-poc-sample.csv`](igdb-poc-sample.csv) | Frozen 60-case control sample |
| [Synthetic usability synthesis](simulated-round-synthesis.md) | Prototype record, accepted internal journey decision, and limitations |
| [IGDB PoC tool](../../tools/igdb-poc/README.md) | Reproducible capture/offline-validation procedure |
| [Performance baseline](performance-baseline/README.md) | Measured catalogue, synthetic dataset profiles and first low-rate public HTTP baseline for #85 |
| [Historical synchronization pilot](historical-sync-pilot.md) | #246 spike: method, measurements available from existing instrumentation, remaining gaps and how pilot evidence refines chunking, checkpointing, concurrency and limits; tooling prepared, no real pilot yet |
| [Portable production candidate acceptance](public-production-candidate-acceptance.md) | #275 increment 8 frozen gate matrix, sanitized evidence, open owner decisions and #276 handoff; no purchase/exposure authorization |
| [Public-production topology investigation](public-production-topology.md) | #252 hosting comparison, provider/privacy review, evidence limits and draft #251 decomposition; no infrastructure or purchase |
| [Public-release rights and privacy](public-release-rights-and-privacy.md) | #274: IGDB/Twitch terms per content type, verified personal-data inventory, GDPR/Spanish requirements, revocation comparison and deletion-ledger design, licence contingency, approved decisions, open uncertainties, launch blockers, and the phase 2 plan with the production-realm evidence and the transactional email provider comparison; no legal advice |

## Point-in-time reviews

AI-assisted repository reviews at a fixed revision. They are frozen inputs for the
owner's triage, not approved decisions or current state; findings that the owner
accepted became GitHub issues, and anything not turned into an issue was not adopted.
Do not update them when the code changes.

| Review | Revision |
|---|---|
| [Gameómetro user-facing copy audit](reviews/user-facing-copy-audit-2026-10-19.md) | `1532f1d`, 2026-10-19; #286 phase 1, inventory and proposals pending owner review |
| [Repository quality review](reviews/repository-quality-review-2026-08-27.md) | `9a23801`, 2026-08-27 |
| [MVP close-out: architecture](mvp-closeout-review/architecture-review.md), [code](mvp-closeout-review/code-review.md), [DevOps](mvp-closeout-review/devops-review.md), [infrastructure](mvp-closeout-review/infrastructure-review.md), [observability](mvp-closeout-review/observability-review.md) | `d10bc44`, MVP close-out |

Do not commit personal data, provider secrets, raw authenticated responses, or
material that cannot be stored in the public repository.
