# Learning MVP domain model

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Provider-independent conceptual model; not Java, SQL, HTTP, or UI

## Boundaries and concepts

`Catalogue and Releases` owns the bounded catalogue, games, aliases, covers,
commercial releases, platform/region taxonomy, external references, provenance,
verification, review, and freshness. `Ratings` owns personal rating lifecycle,
eligibility, and aggregate calculation. Identity supplies only authenticated
`UserId`; provider credentials, sessions, and accounts are outside the domain.

| Concept | Meaning |
|---|---|
| `Game` / `GameId` | Curated product work and provider-independent identity |
| `GameAlias` | Localized/alternative/historical/product-curated title resolving to one game |
| `CoverReference` | Approved `provider_cdn_reference` or product-owned fallback with provenance, alt text, usage status, and check time |
| `Release` / `ReleaseId` | One coherent commercial game + platform + region + date + status tuple |
| `Availability` | Subscription/promotion access; defined only to prevent confusion with `Release` |
| `ReleaseDate` | Closed day, month, quarter, year, or unknown value; precision is never invented |
| `ExternalReference` | Typed provider/entity/provider-ID link; never product identity |
| Verification / review / freshness | Independent evidence, ambiguity, and time-policy states |
| `Rating` | One active integer 1–10 identified by `UserId + GameId` |
| `RatingStatistics` | Unweighted mean, count, and 1–10 distribution from active ratings |

Provider results and import candidates remain outside the domain until curation.
Every visible game resolves to an approved cover or fallback. Provider cover binaries
are never copied, proxied, persisted, committed, or redistributed.

## Rating policies

A game is globally eligible when at least one release is `released`, does not require
review, and its temporal threshold has passed. Known dates may use `provider_only` or
`verified` evidence; an unknown date requires `verified` evidence. Eligibility starts
on the exact day, after the represented month/quarter/year ends, or immediately for a
verified unknown-date release marked released. Freshness alone does not revoke a
historical release fact. Create/update re-evaluate eligibility; the owner may always
delete an existing rating.

Eligibility reasons are `ELIGIBLE_RELEASE_FOUND`, `NO_COMMERCIAL_RELEASE`,
`RELEASE_NOT_OCCURRED`, `RELEASE_CANCELLED`, `RELEASE_DATE_UNCERTAIN`, and
`RELEASE_REVIEW_REQUIRED`. Evaluation uses an explicit application-provided date in
`Europe/Madrid`.

Statistics use only active ratings. Count equals the distribution sum; count zero has
no numeric mean; exposed mean is half-up to one decimal. Spanish presentation changes
the decimal separator, not the numeric contract.

## Canonical invariant register

Downstream documents may reference these IDs but must not redefine them.

| ID | Rule |
|---|---|
| `CAT-001` | Every domain game is a curated member of the bounded catalogue. |
| `CAT-002` | Provider results/candidates do not become games before curation. |
| `CAT-003` | Provider failure/miss never removes a supported game automatically. |
| `GAME-001` | Every game uses a provider-independent `GameId`. |
| `GAME-002` | Canonical title is non-blank. |
| `GAME-003` | Slug is navigation, not identity. |
| `GAME-004` | An alias resolves to one game and does not create identity. |
| `GAME-005` | Spanish aliases/editorial content retain product ownership/provenance. |
| `GAME-006` | Every visible game resolves to an approved primary cover. |
| `GAME-007` | Primary cover is an approved provider reference or product fallback. |
| `GAME-008` | Provider cover references retain provenance and usage status. |
| `GAME-009` | Pending/unavailable provider covers are not displayed. |
| `GAME-010` | Provider covers are references; binaries are not product-stored. |
| `GAME-011` | Provider covers use only the allowlisted documented image host. |
| `GAME-012` | Provider cover display includes attribution and source path. |
| `GAME-013` | Cover references contain no credential/token/authenticated URL. |
| `GAME-014` | Cover failure selects fallback without hiding the game. |
| `GAME-015` | Approval is scoped to usage/release mode and asserts no ownership. |
| `GAME-016` | Only an approved alias is discoverable; pending/rejected aliases are not. |
| `GAME-017` | Comparable search text is derived; the stored display title is never rewritten. |
| `REL-001` | A release belongs to exactly one game. |
| `REL-002` | A release has one platform and one region or explicit unknown. |
| `REL-003` | Date value and precision form one valid closed variant. |
| `REL-004` | Presentation never exceeds known date precision. |
| `REL-005` | Separate provider records are never merged silently. |
| `REL-006` | Availability is never a commercial release. |
| `REL-007` | Cancelled releases never prove rating eligibility. |
| `REL-008` | Provider releases retain provenance, timestamps, verification, and review. |
| `REL-009` | Unknown release information remains explicit. |
| `REL-010` | Verification, review, and freshness remain independent. |
| `REL-011` | Time policies receive explicit time/zone, never host defaults. |
| `EXT-001` | Provider ID is a reference, not internal identity. |
| `EXT-002` | Provider taxonomy does not become the public product contract. |
| `EXT-003` | Provider failure preserves last valid local data. |
| `EXT-004` | A typed external reference maps to at most one internal concept. |
| `RAT-001` | Rating value is an integer 1–10. |
| `RAT-002` | At most one active rating exists per user/game. |
| `RAT-003` | Create requires authentication. |
| `RAT-004` | Only the owner updates/deletes. |
| `RAT-005` | Create/update require eligibility; owner delete does not. |
| `RAT-006` | Update changes the existing active rating. |
| `RAT-007` | Delete removes personal and aggregate contribution. |
| `RAT-008` | Invalid/failed operations preserve prior valid state. |
| `RAT-009` | Personal and aggregate ratings are distinct. |
| `RAT-010` | Rating identity is `UserId + GameId`, not a surrogate. |
| `AGG-001` | Statistics use only active valid personal ratings. |
| `AGG-002` | Count equals the distribution-bucket sum. |
| `AGG-003` | Count zero has no numeric mean. |
| `AGG-004` | Mean is unweighted arithmetic mean. |
| `AGG-005` | Exposed mean is half-up to one decimal. |
| `USR-001` | `Mis puntuaciones` exposes only the authenticated user's ratings. |
| `USR-002` | User scoping precedes personal search/sort/pagination. |

Physical deletion/audit, aggregate materialization, operational stale thresholds,
and import/curation UI remain implementation or later product decisions.
