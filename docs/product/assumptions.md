# Product assumptions

- **Status:** Active
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-07-24

This register contains beliefs, not facts. Evidence should link to research notes or
measured product data. `Impact` describes the consequence if the assumption is false.

| ID | Assumption | Impact | Uncertainty | Decision basis / next check | Status |
|---|---|---:|---:|---|---|
| A-001 | Release-aware players look for relevant upcoming or recent releases at least monthly or around events | High | High | Accepted for the learning MVP from the [synthetic interview synthesis](../research/phase-1-user-interviews.md#62-plausible-insights-to-investigate); observe repeat use in a small pilot | Accepted risk |
| A-002 | Repeated, fragmented research and lost personal context create enough friction to justify a focused journey | High | High | Direction is consistent with the [synthetic interviews](../research/phase-1-user-interviews.md#insight-2--fragmentation-may-be-a-symptom-rather-than-the-core-problem) and [competitor comparison](../research/competitor-journey-comparison-metacritic.md#7-main-gaps-and-product-opportunities); test in prototype tasks | Accepted risk |
| A-003 | Combining release discovery, a concise game page, rating, and rating retrieval provides more value than any element alone | High | High | Implement only as a thin prototype/MVP journey and compare where users leave or return | Accepted risk |
| A-004 | Release-aware, Spanish-speaking multiplatform players who already track games are the best first segment | High | Medium | Selected as the narrowest segment coherent with the synthetic synthesis; revisit only if lightweight tests contradict it | Accepted risk |
| A-005 | Users obtain personal value from retaining and retrieving their ratings | High | High | `Mis puntuaciones` closes the gap identified in the [competitor journey](../research/competitor-journey-comparison-metacritic.md#72-complete-the-personal-value-loop); test create, retrieve, edit, and delete | Accepted risk |
| A-006 | One authorised provider can supply enough reliable game, platform, image, and release data for the learning MVP | High | High | The [documentary provider spike](../research/game-data-providers-spike.md) selects IGDB for the first authenticated PoC and RAWG as fallback; validate the defined sample, record the applicable non-commercial terms, and obtain provider clarification for public or monetized use and image rights before that release mode | In validation |
| A-007 | A mobile-first responsive web product is sufficient for the learning MVP | Medium | Medium | Chosen for operational simplicity; validate on the devices used in prototype sessions | Accepted risk |
| A-008 | A deliberately bounded catalogue can validate the primary journey | Medium | Medium | Declare coverage explicitly and observe missing-title failures | Accepted risk |
| A-009 | A Spanish-first, region- and platform-aware experience is meaningfully clearer than a generic global release calendar | Medium | High | Direction comes from the [competitor positioning](../research/competitor-journey-comparison-metacritic.md#8-proposed-positioning); treat language as product behaviour and test comprehension | Accepted risk |

## Evidence standard

An assumption may move to `Supported` only when the evidence and its limitations are
linked. Conflicting evidence should remain visible. `Supported` does not mean proven
permanently; assumptions should be revisited when the audience or product changes.

`Accepted risk` is an explicit owner decision to proceed for learning value without
claiming that user demand has been demonstrated. The synthetic interviews are useful
design input, but they are not admissible user evidence.

Allowed statuses:

- `Unvalidated`
- `In validation`
- `Supported`
- `Rejected`
- `Accepted risk`
