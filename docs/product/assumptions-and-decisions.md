# Product assumptions and resolved questions

- **Status:** Active register; MVP alignment is closed
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
| A-009 | IGDB use is compatible with the release mode | High | Supported for private use; public rights uncertainty accepted by owner | [Provider spike](../research/game-data-providers-spike.md) and ADR-0001; [public-use investigation](../research/public-production-topology.md#provider-and-image-review) is historical evidence; the [Product Brief](product-brief.md#public-release-and-sustainability) owns the superseding public risk acceptance. The [#274 review](../research/public-release-rights-and-privacy.md#1-igdb-and-provider-rights) leaves translations, decorative media, scores and retention unconfirmed; the owner's query to IGDB support awaits a reply |
| A-010 | Separate aggregate and personal scores plus inline 1–10 interaction are understandable | High | Supported | [Synthetic synthesis](../research/simulated-round-synthesis.md); not real-user evidence |
| A-011 | IGDB Hypes among distinct new Full Releases surfaces a month's relevant launches (#151) | Medium | Accepted risk | Owner-approved after real September/October comparison; keep observing eligible launches and coverage, without bypassing review/evidence rules (#226); [ADR-0021](../decisions/0021-rank-monthly-featured-releases-by-local-igdb-hypes.md) |
| A-012 | A weekly TOPómetro (then Destacados) landing gives more reason to return than one selection that stays unchanged for a month (#329) | Medium | Accepted risk | Owner chose the week as the landing granularity; no repeat-visit evidence exists and none is claimed. A week may legitimately repeat games and may hold few candidates. [#330](https://github.com/rubhern/videogame-platform/issues/330) owns whether return visits can be measured responsibly |

## Resolved questions

| ID | Decision | Reopen when |
|---|---|---|
| Q-001 | The translated [source vision](../reference/video-game-platform-vision.pdf) is the historical input; the Product Brief is the approved narrowing | Fidelity to the source is disputed |
| Q-002 | Priority user: release-aware Spanish-speaking multiplatform player who already tracks games | Evidence supports a materially different first segment |
| Q-003 | Accepted MVP problem: fragmented release research plus lost personal rating continuity; the [Product Brief](product-brief.md#priority-user-and-problem) owns the evolving problem hypothesis | Real evidence rejects or reframes the problem |
| Q-004 | Accepted MVP value: Spanish-first platform/region clarity plus complete personal rating loop; current positioning is owned by the [Product Brief](product-brief.md#priority-user-and-problem) | A different differentiator gains evidence |
| Q-005 | IGDB for bounded private learning use, with explicit provenance/review state on uncertain dates, product-owned Spanish aliases, local normalized data, attributed direct CDN covers and, for featured discovery, artworks, screenshots and logos ([ADR-0001](../decisions/0001-reference-igdb-cover-images.md), amended 2026-10-03), no copied binaries. External scores were outside the accepted MVP; Q-012 records the approved post-MVP direction | Public/monetized release, retention/redistribution beyond approved normalized metadata/CDN references, copied/stored image binaries, acquisition beyond the bounded synchronization of [ADR-0017](../decisions/0017-discover-catalogue-members-automatically-from-igdb.md), or material terms change |
| Q-006 | One active integer 1–10 rating per user/released game; inline edit/delete; aggregate and personal values separate; current score presentation is owned by the [Product Brief](product-brief.md#approved-mvp) | Usability or domain evidence contradicts the model |
| Q-007 | One part-time human owner, no fixed launch date or current service contract; the [Product Brief](product-brief.md#public-release-and-sustainability) owns the approved budget and deferred purchase | Capacity, ownership, schedule, or budget changes |
| Q-008 | The [Product Brief](product-brief.md#public-release-and-sustainability) owns the approved open, non-commercial, best-effort next release direction; current operation remains private | Public acceptance gates, commercial scope, or operating cost decisions change |
| Q-009 | Journey gate is `PASS`: accepted synthetic 4/5 plus focused regression with no blocker | Journey rules, evidence objective, or release mode changes |
| Q-010 | Synthetic evidence is decision-grade only for this internal learning workflow | A claim about real users/demand is needed |
| Q-011 | The user-facing product name is **Gameómetro** (#152), accented wherever users see it; `VideoGame Platform` and its technical identifiers remain the repository and system names | The owner renames the product |
| Q-012 | Owner-approved post-MVP external-score and presentation policy is owned by the [Product Brief](product-brief.md#trustworthy-quality-and-community-direction), supported by the [#256 research](../research/external-quality-signals.md); #255 owns implementation | Provider terms or score semantics change, representative catalogue evidence contradicts suitability, or the owner revises presentation; public non-commercial risk treatment follows the [Product Brief](product-brief.md#public-release-and-sustainability); commercial use remains unapproved |
| Q-013 | Public accounts are adult-only (18+) by explicit declaration, with email sign-in, no required names and manual rights procedures; the [Product Brief](product-brief.md#public-release-and-sustainability) owns the baseline | Legal review contradicts it, a younger audience or public profiles are sought, or request volume needs self-service; approved thirty-day pending-signup cleanup is covered by the linked baseline |

Architectural consequences are recorded in [ADRs](../decisions/README.md). Add a new
question only when it can materially change product direction.
