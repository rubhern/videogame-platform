# Product assumptions

- **Status:** Active
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-07-27

This register contains beliefs, not facts. Evidence should link to research notes or
measured product data. `Impact` describes the consequence if the assumption is false.

| ID | Assumption | Impact | Uncertainty | Decision basis / next check | Status |
|---|---|---:|---:|---|---|
| A-001 | Release-aware players look for relevant upcoming or recent releases at least monthly or around events | High | High | Accepted for the learning MVP from the [synthetic interview synthesis](../research/phase-1-user-interviews.md#62-plausible-insights-to-investigate); observe repeat use in a small pilot | Accepted risk |
| A-002 | Repeated, fragmented research and lost personal context create enough friction to justify a focused journey | High | High | Direction is consistent with the [synthetic interviews](../research/phase-1-user-interviews.md#insight-2--fragmentation-may-be-a-symptom-rather-than-the-core-problem) and [competitor comparison](../research/competitor-journey-comparison-metacritic.md#7-main-gaps-and-product-opportunities); observe the real tasks in the [prototype test](../research/prototype-usability-test-guide.md) | Accepted risk |
| A-003 | Combining release discovery, a concise game page, rating, and rating retrieval provides more value than any element alone | High | High | The complete journey exists in the [clickable prototype](clickable-prototype.md); observe where real users leave, hesitate, or state a credible return purpose | Accepted risk |
| A-004 | Release-aware, Spanish-speaking multiplatform players who already track games are the best first segment | High | Medium | Selected as the narrowest segment coherent with the synthetic synthesis; revisit only if lightweight tests contradict it | Accepted risk |
| A-005 | Users obtain personal value from retaining and retrieving their ratings | High | High | `Mis puntuaciones` closes the gap identified in the [competitor journey](../research/competitor-journey-comparison-metacritic.md#72-complete-the-personal-value-loop); test create, retrieve, edit, and delete with real users | Accepted risk |
| A-006 | One provider can supply enough game, platform, image-reference, and release data for a bounded learning MVP | High | Medium | The [first authenticated IGDB PoC](../research/igdb-poc-results.md) passed core identity, platform, region, provenance, offline, security, and operational metrics. Release accuracy was 83.1% and localized-title coverage 40%; Ruben accepts manual release reconciliation and product-owned Spanish aliases for the bounded catalogue | Supported |
| A-007 | A mobile-first responsive web product is sufficient for the learning MVP | Medium | Medium | The [prototype](clickable-prototype.md) is mobile-first; validate it on the devices used in participant sessions | Accepted risk |
| A-008 | A deliberately bounded catalogue can validate the primary journey | Medium | Medium | The prototype exposes eight curated, navigable game pages; observe browsing, zero-result recovery, missing-title trust, and boundary comprehension | Accepted risk |
| A-009 | A Spanish-first, region- and platform-aware experience is meaningfully clearer than a generic global release calendar | Medium | High | Direction comes from the [competitor positioning](../research/competitor-journey-comparison-metacritic.md#8-proposed-positioning); treat language as product behaviour and test comprehension | Accepted risk |
| A-010 | IGDB is compatible with the current private, non-commercial learning mode using local normalized metadata without copied images or external ratings | High | Medium | Supported for the explicitly bounded release mode by the [provider spike](../research/game-data-providers-spike.md#66-terms-and-release-mode-boundary). Public deployment, monetization, copied images, or redistribution must reopen the provider gate | Supported |
| A-011 | Users understand an aggregate mean and their personal integer rating when both remain visible, separately labelled, and shown without `/10` | High | High | Owner-directed interaction rule represented in the prototype; test explicit comprehension before defining the rating contract | Accepted risk |
| A-012 | An inline 1–10 selector reduces rating friction without hiding eligibility, authentication, edit, or delete behaviour | High | High | Observe discoverability, hesitation, authentication return, and direct maintenance in the prototype test | Accepted risk |

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
