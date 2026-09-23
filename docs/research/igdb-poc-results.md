# First authenticated IGDB PoC

- **Status:** Accepted `CONDITIONAL_PASS`
- **Execution:** 2026-07-24
- **Control sample:** [`igdb-poc-sample.csv`](igdb-poc-sample.csv), 60 frozen cases
- **Tool:** [IGDB PoC](../../tools/igdb-poc/README.md)

## Result

| Evidence | Result |
|---|---:|
| Cases | 41 pass, 9 review, 10 fail |
| Exact-title search | 98.0% |
| Platform | 100% |
| Region known or explicit | 100% |
| Release date/precision | 83.1% plus two reviews; below the frozen 90% blocker |
| Alternative/localized title | 40%; below the non-blocking 80% target |
| Metadata/provenance/timestamp | 100% |
| Offline readability | 60/60 |
| Requests | 187; 100% successful; zero HTTP 429; observed p95 561 ms |
| Secrets/browser calls | No secrets in output; no browser-to-IGDB call |

The generated result was `CONDITIONAL_PASS`. Release accuracy did not pass its frozen
threshold; continuing is an explicit owner exception for the bounded learning
catalogue, not a reclassification of the metric.

## Findings that changed the model

- Subscription availability had been confused with commercial release in sample
  expectations; they are now separate concepts.
- One-day differences require explicit timezone/region reconciliation.
- Generic `PC` can incorrectly merge DOS and modern Windows releases.
- Provider data can be more precise or differently classified than a frozen official
  expectation; preserve source values and review instead of forcing certainty.
- Six of ten expected Spanish aliases were absent; aliases/editorial Spanish remain
  product-owned.
- One title was missing, which is acceptable only with an explicit bounded catalogue
  and honest zero-result state.
- The live run did not exercise an applicable cancelled/delayed case; that behaviour
  requires fixture or later evidence before being claimed from this run.

## Accepted limitations

Use a curated catalogue, manually reconcile displayed recent/upcoming dates, preserve
precision/provenance/unknown/review states, maintain Spanish aliases locally, and
serve the normalized last-valid snapshot. The run does not justify broad unattended
ingestion, request-path provider calls, another provider, external ratings, or a
provider microservice.

The owner accepts IGDB for the release mode defined in the
[provider spike](game-data-providers-spike.md) and ADR-0001. Public/commercial or
copied/stored/redistributed use must reopen the decision and recheck current terms.

Re-run offline validation after normalization-rule changes. Reserve another
authenticated capture for a materially changed sample/provider and record its source
commit; do not overwrite this historical result.
