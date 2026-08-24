# Accepted simulated usability synthesis

- **Status:** Accepted internal evidence
- **Date:** 2026-07-28
- **Prototype:** [Figma](https://www.figma.com/design/DlnALCtbf4zYjcJDF2ixnK)

> The five sessions and focused regression were synthetic. They are accepted only to
> exercise an internal product-decision workflow. They do not represent real
> participants, demand, retention, or product–market fit.

## Decision

Four of five simulated participants completed release discovery → game page → rating
→ `Mis puntuaciones` unaided. The initial blocking inconsistency was corrected and a
focused simulated regression verified the changed path. The learning-project journey
gate is `PASS`; this was not a second five-participant round.

## Retained findings

| Finding | Resolution |
|---|---|
| Pre-rating card incorrectly showed a personal `9` | All pre-rating states now say `Sin puntuar`; numeric personal values appear only after save |
| Zero-result action looked disabled | Active `Ver resultados` action and direct eight-game boundary explanation |
| Provenance/freshness/precision wording was technical | Plain labels: source, date detail, last review |
| Rating edit feedback was ambiguous | Explicit success state before returning to updated list |
| Future-platform wording conflicted | Consistent confirmed/pending platform copy |
| Text-only cards slowed recognition | Distinct prototype visual identifiers; provider-cover implementation decided separately |
| Catalogue explanation was easy to miss | Boundary stated directly in zero-result copy |
| Traceability felt repetitive | One compact source/date/review block |

All five simulated participants distinguished aggregate from personal rating and
understood the unreleased-game rule. The personal-list navigation, delete confirmation,
empty state, and bounded catalogue framing were understandable within the simulation.

Contradictory evidence remains: rich provenance increased trust for one profile and
felt technical to others; strong editorial visuals helped hierarchy but some expected
real covers; two wanted wishlist/interest behaviour before release despite
understanding the rule. These are inputs, not automatic MVP additions.

The focused regression verified consistent pre-rating/saved/edit/delete values,
zero-result recovery, ambiguous release copy, decimal-comma/no-`/10` formatting, and
navigation to all eight game pages. Implementation still requires real accessibility,
browser, identity, persistence, failure, and responsive evidence.
