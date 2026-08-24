# Product assumptions

- **Status:** Active
- **Owner:** Ruben Hernandez

`Supported` means supported only within the linked evidence and its limitations;
`Accepted risk` means the owner proceeds for learning value without claiming proof.

| ID | Assumption | Impact | Status | Evidence / next check |
|---|---|---:|---|---|
| A-001 | Release-aware Spanish-speaking multiplatform players have the selected research/context problem | High | Accepted risk | Synthetic framing and [competitor comparison](../research/competitor-journey-comparison-metacritic.md); test with real users before demand claims |
| A-002 | Discovery → game context → rating → retrieval is more useful than isolated features | High | Accepted risk | Prototype completion supports usability only; observe real return behaviour |
| A-003 | The selected priority segment is the best first segment | High | Accepted risk | Owner decision; revisit if lightweight real evidence contradicts it |
| A-004 | Retaining and retrieving ratings creates personal value | High | Accepted risk | Synthetic completion does not prove value or retention |
| A-005 | One provider supports a bounded MVP | High | Supported | [IGDB PoC](../research/igdb-poc-results.md); manual date reconciliation and Spanish aliases remain required |
| A-006 | A mobile-first responsive web product is sufficient | Medium | In validation | Prototype was phone-sized; implementation must prove accessibility, real browser/device, and responsive behaviour |
| A-007 | Users understand a declared eight-game/bounded catalogue | Medium | Supported | Accepted synthetic round; keep scope and zero-result explanation explicit |
| A-008 | Spanish-first platform/region context is meaningfully clearer | Medium | Accepted risk | Competitor evidence suggests the opportunity; validate comprehension and value |
| A-009 | IGDB use is compatible with the current release mode | High | Supported | [Provider spike](../research/game-data-providers-spike.md) and ADR-0001; reopen for public/commercial/copied/stored/redistributed use |
| A-010 | Separate aggregate and personal scores plus inline 1–10 interaction are understandable | High | Supported | [Synthetic synthesis](../research/simulated-round-synthesis.md); not real-user evidence |

Move a status only with linked evidence and preserve contradictory findings. No
synthetic source may substantiate real behaviour, demand, retention, or product–market
fit.
