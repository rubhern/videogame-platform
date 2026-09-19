# Product assumptions and resolved questions

- **Status:** Active register; product alignment is closed
- **Owner:** Ruben Hernandez

This is a compact register of what the product still assumes and what has been
decided, with the condition that reopens each entry. It is not a backlog. Move a
status only with linked evidence and preserve contradictory findings. No synthetic
source may substantiate real behaviour, demand, retention, or product–market fit.

## Assumptions

`Supported` means supported only within the linked evidence and its limitations;
`Accepted risk` means the owner proceeds for learning value without claiming proof.

| ID | Assumption | Impact | Status | Evidence / next check |
|---|---|---:|---|---|
| A-001 | Release-aware Spanish-speaking multiplatform players have the selected research/context problem | High | Accepted risk | Synthetic framing and [competitor comparison](../research/competitor-journey-comparison-metacritic.md); test with real users before demand claims |
| A-002 | Discovery → game context → rating → retrieval is more useful than isolated features | High | Accepted risk | Prototype completion supports usability only; observe real return behaviour |
| A-003 | The selected priority segment is the best first segment | High | Accepted risk | Owner decision; revisit if lightweight real evidence contradicts it |
| A-004 | Retaining and retrieving ratings creates personal value | High | Accepted risk | Synthetic completion does not prove value or retention |
| A-005 | One provider supports a bounded MVP | High | Supported | [IGDB PoC](../research/igdb-poc-results.md); date reconciliation and Spanish aliases remain product-owned |
| A-006 | A mobile-first responsive web product is sufficient | Medium | Supported for the MVP | Implemented and accepted in private dev (`v0.1.0`); real-user evidence still absent |
| A-007 | Users understand a declared bounded catalogue | Medium | Supported | Accepted synthetic round; keep scope and zero-result explanation explicit |
| A-008 | Spanish-first platform/region context is meaningfully clearer | Medium | Accepted risk | Competitor evidence suggests the opportunity; validate comprehension and value |
| A-009 | IGDB use is compatible with the current release mode | High | Supported | [Provider spike](../research/game-data-providers-spike.md) and ADR-0001; reopen for public/commercial/copied/stored/redistributed use |
| A-010 | Separate aggregate and personal scores plus inline 1–10 interaction are understandable | High | Supported | [Synthetic synthesis](../research/simulated-round-synthesis.md); not real-user evidence |

## Resolved questions

| ID | Decision | Reopen when |
|---|---|---|
| Q-001 | The translated [source vision](../reference/video-game-platform-vision.pdf) is the historical input; the Product Brief is the approved narrowing | Fidelity to the source is disputed |
| Q-002 | Priority user: release-aware Spanish-speaking multiplatform player who already tracks games | Evidence supports a materially different first segment |
| Q-003 | First problem: fragmented release research plus lost personal rating continuity, accepted as a hypothesis | Real evidence rejects or reframes the problem |
| Q-004 | Value: Spanish-first platform/region clarity plus complete personal rating loop, not catalogue breadth or professional score | A different differentiator gains evidence |
| Q-005 | IGDB for bounded private learning use, with explicit provenance/review state on uncertain dates, product-owned Spanish aliases, local normalized data, attributed direct CDN covers, no copied binaries/external scores | Public/monetized release, copied/stored/redistributed data/images, acquisition beyond the bounded synchronization of [ADR-0017](../decisions/0017-discover-catalogue-members-automatically-from-igdb.md), or material terms change |
| Q-006 | One active integer 1–10 rating per user/released game; inline edit/delete; aggregate and personal values separate; decimal comma; no `/10` | Usability or domain evidence contradicts the model |
| Q-007 | One part-time human owner, no fixed beta date, no recurring paid commitment | Capacity, ownership, schedule, or budget changes |
| Q-008 | Current initiative is learning-only, not commercial | A commercial/public release is proposed |
| Q-009 | Journey gate is `PASS`: accepted synthetic 4/5 plus focused regression with no blocker | Journey rules, evidence objective, or release mode changes |
| Q-010 | Synthetic evidence is decision-grade only for this internal learning workflow | A claim about real users/demand is needed |

Architectural consequences are recorded in [ADRs](../decisions/README.md). Add a new
question only when it can materially change product direction.
