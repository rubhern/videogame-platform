# Open questions

- **Status:** Closed for Phase 0
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-07-28

Questions are ordered by how strongly they can change product direction. Every
decision is owned by Ruben Hernandez. Synthetic research can inform a decision, but
does not turn a product-demand hypothesis into validated market evidence. The owner
may accept a simulation for an internal learning-project decision when its provenance
and limitations remain explicit.

| ID | Question | Why it matters | Decision or next action | Owner | Decision point | Status |
|---|---|---|---|---|---|---|
| Q-001 | Where is the original high-level product PDF, and is the current brief faithful to it? | It is the stated source for Phase 0 | Copied to `docs/reference/` and reconciled with Product Brief v0.1 on 2026-07-22 | Ruben Hernandez | 2026-07-22 | Resolved |
| Q-002 | Which user segment is the first priority? | It determines the problem, journey, and MVP | Release-aware, Spanish-speaking multiplatform players who research several games per month and already keep a wishlist, backlog, or ratings | Ruben Hernandez | 2026-07-23 | Resolved |
| Q-003 | What problem should the first release solve? | A solution without a focused problem becomes an unfocused catalogue | Reduce repeated research needed to understand relevant recent/upcoming releases and preserve the player's rating in one retrievable journey; accepted as a learning hypothesis, not a validated market problem | Ruben Hernandez | 2026-07-23 | Resolved |
| Q-004 | Why would the chosen user use this product instead of current alternatives? | The value proposition needs a coherent advantage | Spanish-first, region/platform-aware clarity plus a complete personal rating loop; do not compete on catalogue breadth or recreate Metascore | Ruben Hernandez | 2026-07-23 | Resolved |
| Q-005 | Which game-data provider and licence can support the MVP? | Data rights and provider constraints can invalidate the journey | Use IGDB for the private, non-commercial learning MVP with the limitations in the [first authenticated PoC](../research/igdb-poc-results.md): bounded catalogue, manual date reconciliation, product-owned Spanish aliases, local normalized metadata, no copied provider images, and no external ratings. RAWG remains a fallback. Public deployment, monetization, copied images, or redistribution must reopen this question | Ruben Hernandez | 2026-07-24 | Resolved |
| Q-006 | What rating scale, eligibility, display, and aggregation rules are understandable and useful? | This becomes visible product behaviour and a stable contract | Retain one active integer rating from 1 to 10 per user and released game, inline create/edit, explicit delete, separately labelled aggregate and personal ratings, decimal comma, and no `/10`. The [accepted simulated round and focused regression](../research/simulated-round-synthesis.md) support the conceptual separation, explicit edit feedback, and unreleased rule for the current learning scope | Ruben Hernandez | 2026-07-28 | Resolved |
| Q-007 | What are the budget, team capacity, and desired beta horizon? | They constrain scope and operational choices | Personal, part-time project with Ruben as the only human contributor; AI assists simulated roles; no fixed beta date or recurring paid-service commitment; approve spend individually and deliver one vertical slice at a time | Ruben Hernandez | 2026-07-23 | Resolved |
| Q-008 | Is this a learning-only initiative, a commercial product, or both? | It affects success criteria, investment, legal work, and roadmap | Learning-only for the current phase; realistic product validation supports learning but there is no commercial or market-size objective | Ruben Hernandez | 2026-07-23 | Resolved |
| Q-009 | What thresholds will determine continue, change, or stop decisions? | Metrics without thresholds do not guide decisions | The owner accepts the five-session simulation for the private learning-project gate. The accepted round reached 4/5 unaided and the focused simulated regression resolved F-01, so the result is `PASS`. Minimum implementation contracts may begin. This is not external user validation | Ruben Hernandez | 2026-07-28 | Resolved |
| Q-010 | What evidence standard applies to the prototype journey gate in this private training product? | Treating simulation as real-user research would create a false claim, while requiring external recruitment is not necessary for the chosen learning objective | Accept `simulated-session-observation-sheets.md` and `simulated-round-synthesis.md` as decision-grade internal evidence by explicit owner decision. Preserve their synthetic provenance in every downstream claim; they cannot substantiate real-user behaviour, demand, retention, or product–market fit | Ruben Hernandez | 2026-07-28 | Resolved |

## Decision log convention

When a question is resolved, keep the row, set its status to `Resolved`, and link the
resulting document or decision. Architectural decisions belong in `docs/decisions/`
only after the product context makes them necessary.
