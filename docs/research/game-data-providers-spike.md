# Game-data provider spike

- **Status:** Decision complete for the private learning MVP
- **Reviewed:** 2026-07-23; cover/release-mode boundary refreshed 2026-07-29

## Decision

Use IGDB as the first and only provider for a bounded, private, non-commercial
learning catalogue. RAWG remains a documentary fallback. Serve normalized local data;
never call a provider from visitor/rating request paths or expose provider identity as
product identity.

The decision is conditional on the [authenticated PoC](igdb-poc-results.md), the
product's manual reconciliation of ambiguous recent/upcoming releases, product-owned
Spanish aliases/editorial content, and the cover rules in
[ADR-0001](../decisions/0001-reference-igdb-cover-images.md).

## Comparison

| Criterion | IGDB | RAWG | Decision relevance |
|---|---|---|---|
| Game/release model | Rich platform/region/date/relationship model | Broad conventional REST catalogue | IGDB better fits normalized releases |
| Integration | Twitch OAuth + APICalypse, documented rate constraints | API-key REST and standard pagination | RAWG is simpler |
| Incremental/local use | Updated timestamps, webhooks, documented caching | Updated filters and monthly-plan boundary | IGDB better fits local synchronization |
| Spanish/localization | Localization/language entities but incomplete Spanish titles/editorial text | No stronger documented Spanish guarantee | Product-owned Spanish layer required either way |
| Terms/cost at review date | Suitable for bounded evaluation with attribution/partnership conditions requiring care | Free personal/hobby plan plus attribution; commercial wording/price boundary was less clear | Neither approves arbitrary public/commercial reuse |
| Images/scores | CDN references available; rights/attribution/storage still constrained | Image/Metacritic exposure does not prove reuse rights | No copied binaries or external scores |

The original weighted assessment was IGDB 82/100 and RAWG 69.5/100. The numbers are
historical decision aids, not provider facts or a current procurement score.

## Required provider boundary

- Internal `GameId`/`ReleaseId` remain independent from provider IDs.
- Release means commercial platform/region release; subscription availability is a
  separate concept.
- Preserve date precision, provenance, provider update, synchronization, verification,
  review, and freshness state.
- Provider records are untrusted candidates until normalized/validated. Conflicts and
  unknowns remain explicit; failed sync preserves the last valid local snapshot.
- Synchronization is bounded and operator/scheduler initiated. New games and material
  cover changes require explicit curation/review.
- Direct IGDB covers use allowlisted host/size/reference construction, visible
  attribution/source path, and product fallback. Provider image binaries are not
  copied, proxied, persisted, committed, or redistributed.

## Release-mode boundary

Approved only for local normalized metadata, direct attributed CDN cover references,
and no external ratings in a private non-commercial learning release. Before public,
monetized, copied-image, application-storage, redistribution, or broad unattended
use, recheck current provider terms and partnership, attribution, retained-data, and
image requirements. Historical terms in this spike are not an evergreen legal or
commercial authorization.

## Evidence and triggers

The frozen 60-case sample and measured outcome are in
[the PoC result](igdb-poc-results.md). Reconsider IGDB or evaluate RAWG only when
provider constraints materially change, accepted manual reconciliation becomes too
costly, coverage blocks the bounded journey, or the release mode changes.
