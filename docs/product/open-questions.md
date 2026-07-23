# Open questions

- **Status:** Active
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-07-23

Questions are ordered by how strongly they can change product direction. Every
decision is owned by Ruben Hernandez. Synthetic research can inform a decision, but
does not turn a product-demand hypothesis into validated evidence.

| ID | Question | Why it matters | Decision or next action | Owner | Decision point | Status |
|---|---|---|---|---|---|---|
| Q-001 | Where is the original high-level product PDF, and is the current brief faithful to it? | It is the stated source for Phase 0 | Copied to `docs/reference/` and reconciled with Product Brief v0.1 on 2026-07-22 | Ruben Hernandez | 2026-07-22 | Resolved |
| Q-002 | Which user segment is the first priority? | It determines the problem, journey, and MVP | Release-aware, Spanish-speaking multiplatform players who research several games per month and already keep a wishlist, backlog, or ratings | Ruben Hernandez | 2026-07-23 | Resolved |
| Q-003 | What problem should the first release solve? | A solution without a focused problem becomes an unfocused catalogue | Reduce repeated research needed to understand relevant recent/upcoming releases and preserve the player's rating in one retrievable journey; accepted as a learning hypothesis, not a validated market problem | Ruben Hernandez | 2026-07-23 | Resolved |
| Q-004 | Why would the chosen user use this product instead of current alternatives? | The value proposition needs a coherent advantage | Spanish-first, region/platform-aware clarity plus a complete personal rating loop; do not compete on catalogue breadth or recreate Metascore | Ruben Hernandez | 2026-07-23 | Resolved |
| Q-005 | Which game-data provider and licence can support the MVP? | Data rights and provider constraints can invalidate the journey | Compare at least two plausible authorised providers, then run one small import spike covering required fields, storage/display/image rights, attribution, limits, freshness, and cost | Ruben Hernandez | Before catalogue implementation | Open |
| Q-006 | What rating scale and aggregation rules are understandable and useful? | This becomes visible product behaviour and a stable contract | Use integer scores from 1 to 10; one active rating per user and game; allow edit/delete; show arithmetic mean to one decimal, count, and distribution; no weighting or platform-specific aggregate in the MVP | Ruben Hernandez | 2026-07-23 | Resolved |
| Q-007 | What are the budget, team capacity, and desired beta horizon? | They constrain scope and operational choices | Personal, part-time project with Ruben as the only human contributor; AI assists simulated roles; no fixed beta date or recurring paid-service commitment; approve spend individually and deliver one vertical slice at a time | Ruben Hernandez | 2026-07-23 | Resolved |
| Q-008 | Is this a learning-only initiative, a commercial product, or both? | It affects success criteria, investment, legal work, and roadmap | Learning-only for the current phase; realistic product validation supports learning but there is no commercial or market-size objective | Ruben Hernandez | 2026-07-23 | Resolved |
| Q-009 | What thresholds will determine continue, change, or stop decisions? | Metrics without thresholds do not guide decisions | Continue while the next slice has explicit product and learning value; change provider/scope if legal rights or essential data fail; iterate if fewer than 4 of 5 prototype users complete the core journey unaided; do not expand until the slice is tested, observable, and documented | Ruben Hernandez | 2026-07-23 | Resolved |

## Decision log convention

When a question is resolved, keep the row, set its status to `Resolved`, and link the
resulting document or decision. Architectural decisions belong in `docs/decisions/`
only after the product context makes them necessary.
