# Learning MVP domain model

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Provider-independent conceptual model; not Java, SQL, HTTP, or UI

## Boundaries and concepts

`Catalogue and Releases` owns the bounded catalogue, games, aliases, covers,
commercial releases, platform/region taxonomy, external references, external player
scores, provenance, verification, review, and freshness, and, post-MVP (#295), each
user's preferred platforms over that taxonomy. `Ratings` owns personal rating
lifecycle, including the platforms a rating names (#359), eligibility, and aggregate
calculation. Identity supplies only authenticated
`UserId`; provider credentials, sessions, and accounts are outside the domain.

| Concept | Meaning |
|---|---|
| `Game` / `GameId` | Accepted product work and provider-independent identity |
| `GameAlias` | Localized/alternative/historical/product-curated title resolving to one game |
| `CoverReference` | Approved `provider_cdn_reference` or product-owned fallback with provenance, alt text, usage status, and check time |
| `Release` / `ReleaseId` | One coherent commercial game + platform + region + date + status tuple. Persisted status is provider evidence (`announced` for no negative signal, `delayed`, `cancelled`, or explicit `released`); `scheduled`/`released` for a known date are derived per request from the date and the trusted evaluation date |
| `Availability` | Subscription/promotion access; defined only to prevent confusion with `Release` |
| `ReleaseStage` | Semantic type of a release date: Full Release, Early Access, Advance Access, Beta, Alpha, or Unknown. Distinct from lifecycle status, work category, and edition; never inferred from dates. |
| `ReleaseDate` | Closed day, month, quarter, year, or unknown value; precision is never invented |
| `ExternalReference` | Typed provider/entity/provider-ID link; never product identity |
| Popularity signal | One game's current provider-observed attention (IGDB Hypes) with its source and acquisition time; ranks featured releases, never measures quality |
| External player score | One source's own player mean of a game on its declared 0–100 scale with the positive count of that source's ratings (currently IGDB players), attributed to the source's page of the work, with its last successful check and last change; external sentiment, never a Gameómetro rating |
| Featured releases | A calendar week's or month's automatic selection: its qualifying-release games ranked by popularity signal, the first being the period's featured release |
| Featured media | Context-selected hero and card landscape images and an optional secondary-card logo, kept as approved CDN references, never binaries |
| Game details | A game's summary, the companies credited as its developers and publishers, its genres and its game modes; current provider-acquired state that may be empty |
| `Company` | A product entity credited with developing or publishing games; one company may hold both roles for one game |
| `Genre` / `GameMode` | Product taxonomy classifying a game's style and ways to play, acquired like platform taxonomy |
| Verification / review / freshness | Independent evidence, ambiguity, and time-policy states; freshness is internal operational evidence of the last committed provider check, never a player-facing state |
| Preferred platforms | One `UserId`'s chosen set of taxonomy platforms that release discovery may apply as an explicit filter, plus whether that user has ever made the choice; never a recommendation, an ownership claim or an inference |
| `Rating` | One active rating identified by `UserId + GameId`: a direct integer 1–10, or, post-MVP (#359), the integer scores its user gave the platforms they played on; either way one contribution to the community |
| Played platform | Post-MVP (#359): a taxonomy platform a user declares having played a rated game on, optionally with that user's own integer 1–10 score there; personal experience of the rating, never an ownership claim or an inference |
| Contribution | A rating's one community vote: its direct value, or the arithmetic mean of its scored platforms, kept unrounded |
| `RatingStatistics` | Unweighted mean of one contribution per rating, the count of ratings, a 1–10 distribution of contributions and, post-MVP (#359), each platform's own reading |

A provider result becomes a `Game` only when it satisfies the explicit import policy;
anything else stays outside the domain. Every visible game resolves to an approved
cover or fallback. Provider cover binaries are never copied, proxied, persisted,
committed, or redistributed.

Release stage is normalized independently of lifecycle. Existing Unknown evidence can
be enriched; missing or unsupported new evidence never erases an established stage.
Conflicting verified stage evidence follows the existing preservation rule. A complete valid
provider Game aggregate defines its current provider-owned release set: missing typed
references are removed atomically with reconciliation. Failed/incomplete/invalid evidence
preserves the entire Game, and curated, official, unreferenced or other-provider evidence
is outside that removal boundary. The product stores current serving state, not a historical
provider ledger. No release
is merged or deleted because it has the same platform, region, or stage as another; the only
same-tuple normalization is `REL-014`.

For provider-only evidence, review is recomputed on every reconciliation from current
uncertainty: an Unknown date or a conflict with the accepted platform/region reference or
known stage requires review. A coherent known date (including approximate precision) does
not, even after a date change or a previous review requirement. Cancellation and delay are
explicit lifecycle evidence; they do not independently imply ambiguity. Review has no
success-count threshold and is not an absorbing state. Verified evidence retains its accepted
review state, and conflicting provider updates are withheld. Missing stage continues to
preserve an established stage; Unknown stage alone does not make a known date uncertain.

## Rating policies

A game is globally eligible when at least one release has effectively occurred and is
neither cancelled, delayed, nor pending review. For a known date, occurrence is derived
from the release date and the trusted evaluation date, not from a provider status
transition: it starts on the exact day and after the represented month/quarter/year
ends. A known date may use `provider_only` or `verified` evidence. An unknown date has
no temporal threshold, so it proves eligibility only through explicit `verified`
`released` evidence. Because occurrence is derived, the passage of time never depends on
catalogue synchronization. Freshness alone does not revoke a historical release fact.
Release stage does not participate in this accepted eligibility policy; a future change
requires a separate product decision. Create/update re-evaluate eligibility; the owner may always delete an existing rating.

Eligibility reasons are `ELIGIBLE_RELEASE_FOUND`, `NO_COMMERCIAL_RELEASE`,
`RELEASE_NOT_OCCURRED`, `RELEASE_CANCELLED`, `RELEASE_DATE_UNCERTAIN`, and
`RELEASE_REVIEW_REQUIRED`. Evaluation uses an explicit application-provided date in
`Europe/Madrid`.

When several releases have different blockers, any eligible release wins. Otherwise
report review required before uncertain date, then release not occurred; report
cancelled only when all releases are cancelled. An empty release set reports no
commercial release. This makes the reason independent of tuple ordering.

Statistics use only active ratings. Count equals the distribution sum; count zero has
no numeric mean; exposed mean is half-up to one decimal. Spanish presentation changes
the decimal separator, not the numeric contract.

Post-MVP (#359, [ADR-0031](../../decisions/0031-count-one-community-vote-per-rating-across-platforms.md)):
a rating has exactly one score source. With a direct value, that value is its
contribution and any played platforms only describe the experience; once a platform
carries a score, the contribution is the arithmetic mean of the scored platforms and there
is no direct value. Each user therefore casts one vote however many platforms they score.
The community mean averages unrounded contributions; each rating sits in exactly one
distribution bucket, its contribution rounded half up to the nearest integer, so a
platform-derived 8.5 counts as 9 while the mean keeps 8.5. A platform's own reading is the
mean of the explicit scores users gave that platform and how many users gave one; a user
counts at most once per platform and may count on several, so platform counts never add
up to voters. Transitions are explicit: a command names the direct value whenever no
platform is left scored, a direct value never silently replaces platform scores, and no
previous direct value is kept to be restored later. A newly named platform must be one of
the game's current release platforms; one already on the rating stays valid whatever
later evidence holds. Existing ratings are direct ratings without platforms; none is
inferred from preferences, devices or releases.

## Canonical invariant register

Downstream documents may reference these IDs but must not redefine them.

| ID | Rule |
|---|---|
| `CAT-001` | Every domain game is an accepted catalogue member with product-owned identity. |
| `CAT-002` | A provider result becomes a game only through the explicit import policy. |
| `CAT-003` | Provider failure/miss never removes or degrades a supported game automatically. |
| `CAT-004` | Importing a work creates its identity, external reference, snapshot, releases, and cover as one accepted state. |
| `CAT-005` | Import is idempotent: one provider reference resolves to at most one game, on any retry. |
| `CAT-006` | A provider work type the product does not model is deferred, not imported and not a failure. |
| `CAT-007` | Platform and region taxonomy is acquired from accepted releases: a provider entity is resolved through a typed external reference, reusing the known product entity or creating it as accepted state, never merged by name/slug ([ADR-0020](../../decisions/0020-acquire-platform-and-region-taxonomy-from-releases.md)). |
| `CAT-008` | A region's display label is catalogue-owned presentation, set when the region is first acquired: the approved Spanish label for its provider descriptor, otherwise a readable form of that descriptor, never a raw technical token. A label never identifies, merges, filters, or resolves a region, and a provider rename does not change it. |
| `CAT-009` | Game details are acquired with the Game through typed external references, never merged by name, and are never invented: a missing value stays absent. A valid provider answer replaces the provider-owned details as a whole; an invalid answer or failed Game preserves the last valid details; product editorial and other-source summaries are outside synchronization ownership. |
| `CAT-010` | Spanish summaries are derived product content with separate original provenance. Curated taxonomy labels are associated with stable references and survive source renames. Machine-derived content is reusable for an unchanged normalized source; failure preserves the source and last valid Spanish content and never makes catalogue serving depend on inference. |
| `GAME-001` | Every game uses a provider-independent `GameId`. |
| `GAME-002` | Canonical title is non-blank. |
| `GAME-003` | Slug is navigation, not identity. |
| `GAME-004` | An alias resolves to one game and does not create identity. |
| `GAME-005` | Spanish aliases/editorial content retain product ownership/provenance. |
| `GAME-006` | Every visible game resolves to an approved primary cover. |
| `GAME-007` | Primary cover is an approved provider reference or product fallback. |
| `GAME-008` | Provider cover references retain provenance and usage status. |
| `GAME-009` | An invalid or unavailable provider cover is never displayed; the fallback is used instead. |
| `GAME-010` | Provider covers are references; binaries are not product-stored. |
| `GAME-011` | Provider covers use only the allowlisted documented image host. |
| `GAME-012` | A provider cover carries attribution and source path, shown on the game page every displayed cover links to. |
| `GAME-013` | Cover references contain no credential/token/authenticated URL. |
| `GAME-014` | Cover failure selects fallback without hiding the game. |
| `GAME-015` | Approval is scoped to usage/release mode and asserts no ownership; it is not a per-image human review. |
| `GAME-018` | A valid provider cover may replace an existing one automatically; an invalid one never does. |
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
| `REL-012` | Whether a release with a known date has happened or is still expected, in discovery and for rating occurrence, derives from the effective release date and the trusted evaluation date; provider status is evidence, never the clock. Synchronization refreshes evidence and never advances time. |
| `REL-013` | A presentation context shows at most one release per platform, chosen by one explicit lifecycle/stage/evidence precedence; every other current release stays stored, unmerged and reachable; removal of obsolete provider-owned references belongs to synchronization, not presentation, and no uniqueness rule limits releases per game and platform. |
| `REL-014` | Within one complete provider Game aggregate, provider references sharing one release tuple are one Release only when exactly one of them has a known stage and every other has none and is unverified: the staged reference is kept and the stage-less restatements are redundant evidence, neither stored nor present. Any other shared tuple is a conflict that rejects the aggregate and preserves its last valid state; an Unknown stage alone never proves duplication. |
| `EXT-001` | Provider ID is a reference, never internal identity, slug, or matching key. |
| `EXT-002` | Provider taxonomy does not become the public product contract. |
| `EXT-003` | Provider failure preserves last valid local data. |
| `EXT-004` | A typed external reference maps to at most one internal concept. |
| `POP-001` | A popularity signal is provider-observed attention: it never states quality, a rating, an award or an editorial recommendation, and its value is not product presentation. |
| `POP-002` | A popularity signal is never invented: a game without one is not ranked and remains a normal catalogue and discovery member. |
| `POP-003` | A valid provider answer records a game's positive signal, or clears it when the provider states zero attention, with that game's accepted state; a game the answer does not mention, an unavailable or invalid answer, or a failed game preserves the last valid signal. |
| `XSC-001` | An external player score belongs to one internal `GameId`, one approved source and one kind, and is never transferred between games, including an original and its remake. Provider identity stays in the external reference. |
| `XSC-002` | A usable score has a value inside its declared 0–100 scale and a positive rating count, including one rating; it is kept without truncation and is never invented, estimated, derived from critics, combined with another population or mixed into the Gameómetro aggregate. |
| `XSC-003` | A complete valid provider answer without a score, or with zero ratings, clears the usable score and still records the check. A partial, out-of-range, malformed, unattributed or failed answer preserves the last valid score and does not advance its last successful check. A game never checked has no score state at all, which differs from a check that found none. |
| `XSC-004` | An unchanged re-check advances only the last successful check, never the last change; neither time is the provider's own score update time. Age alone never invalidates, expires or hides a score, and no minimum rating count applies. |
| `FEAT-001` | A featured candidate for a period (an ISO week, Monday to Sunday, or a calendar month) has a Full Release whose known date lies wholly inside the period (an exact day of it or, for a month, month precision equal to it), neither cancelled, delayed nor pending review; it is an accepted import type and a distinct product rather than an edition, its own known first release is in that period, and its current Hypes count is positive. A week therefore admits exact days only: a month, quarter, year or unknown date is never assigned to a week. Remakes and remasters follow the same rule. Missing evidence never becomes eligibility. |
| `FEAT-002` | Featured ranking orders candidates by Hypes descending, then unique `GameId` ascending; it shows up to six games and never fills a slot artificially. |
| `FEAT-003` | The hero sets the title itself, so hero media prefer high-quality artwork without a provider-declared title, then high-quality screenshot, then high-quality title artwork, then the same three among the other accepted landscape media. Accepted media are opaque, still, at least 640×360 and between 3:2 and 16:5, and never artwork the provider labels as a cover, a game logo or an icon; high quality for the hero means covering a 1280×480 frame. Within each hero step prefer less crop to the 8:3 hero frame, then larger pixel area, then stable image reference ASC. Secondary cards select across both media types: the same accepted gate plus at least 80% retained area at 16:9.4; order by least crop, largest usable pixel area capped at 1280×720, provider-declared title key art before screenshot before ordinary artwork on equal suitability, then stable reference ASC. The provider adapter normalizes the optional image-type label: title key art into a boolean, covers, logos and icons out of the candidates; missing/unknown labels receive no preference. Without a suitable image the hero uses the designed product fallback, which its cover may only light; a card uses an intentional cover treatment, then product fallback. A portrait cover is never distorted to landscape. |
| `FEAT-004` | The hero always renders the canonical title as product-owned text. The first transparent, still logo of at least 160×40 may decorate a secondary screenshot card only. Synchronization stores separate hero and card selections and optional card logo; newer valid evidence replaces each slot independently, while missing, invalid or unavailable media preserve last valid evidence. Reads reject unsuitable legacy selections and use the designed fallback until synchronization replaces them. |
| `RNK-001` | A player-score ranking orders the games of exactly one source by that source's own usable score (`XSC-002`, or the community mean of `AGG-004`); sources, critics and combined scores are never blended, and a game without a usable score is not ranked rather than ranked as zero. |
| `RNK-002` | A ranked game holds one place per source whatever its releases, platforms or regions, and only while a current release proves global rating eligibility and its score rests on the source's minimum sample: 50 ratings for IGDB players, one rating for the Gameómetro community (owner decisions, 2026-10-26; IGDB lowered from 100 on 2026-10-27). The minimum decides ranking only; it never hides the score anywhere it is shown. |
| `RNK-003` | The order is the unrounded mean descending, then the number of ratings behind it descending, then `GameId` ascending; it is a deterministic order, not a confidence formula, and above the source's minimum every sample ranks alike, so a single community rating ranks like any other. |
| `RNK-004` | A place shows the ranked source's own reading with its exact count; it never shows another source's reading in its stead. |
| `RNK-005` | A platform-scoped ranking (#387) ranks only games for which one current release on a platform of the scope proves the eligibility of `RNK-002`, so a port still to come never qualifies a game on its platform; the game keeps one place and its global reading from the source, never a per-platform score. A place presents the eligible scope release chosen by the presentation precedence of `REL-013`, at its own precision. A scope is one product platform or a curated family of them, never a new platform entity (`PCAT-001`). |
| `PCAT-001` | A platform scope is one product platform or a curated family of product platforms, never a new platform entity; a game belongs to it while one current release on a scope platform is not cancelled, and appears once however many such releases, regions or scope platforms it has. Membership claims only what the local catalogue holds, never exhaustive coverage. |
| `PCAT-002` | A game's presented release on a scope is one of its scope releases that has effectively occurred at the trusted evaluation date (`REL-012`), else one with a known date still expected, else a delayed or undated one; inside that class the presentation precedence of `REL-013` chooses, ending with the earliest date. Delayed evidence never occurs. |
| `PCAT-003` | Released and upcoming partition a scope by the presented release's occurrence; a known-year filter reads that release's own year and excludes an unknown date; dates keep their precision, and an unknown date is never ordered as a date. |
| `UPC-001` | An upcoming highlight's qualifying release is a Full Release on a scope platform, neither cancelled, delayed nor pending review, whose known date can only fall after the trusted evaluation date and inside the six-calendar-month horizon: an exact day after it and no later than the horizon's last day, or a month or quarter that has not ended and ends by that day. A year or unknown date is never placed inside the horizon, and no coarser date is narrowed to a day. |
| `UPC-002` | A game is an upcoming highlight candidate of a scope while it has a qualifying release there and no scope release that has effectively occurred (`REL-012`, as `PCAT-002` decides it), and its stored evidence does not make it an edition rather than a distinct product. A release on another platform never disqualifies it. Only a candidate with positive Hypes is ranked (`POP-001`, `POP-002`). |
| `UPC-003` | Upcoming highlights order the ranked candidates by Hypes descending, then `GameId` ascending, show at most ten and never fill a place artificially; each game appears once, presented by one qualifying scope release under the `REL-013` precedence with the earliest date first. |
| `PREF-001` | Preferred platforms belong to exactly one server-derived `UserId`; only that user reads or replaces them, and no other user's request, Keycloak attribute or public read ever carries them. |
| `PREF-002` | A preferred platform is a platform of the local taxonomy; a set is replaced as a whole, holds at most 50 platforms, and an invalid set changes nothing. |
| `PREF-003` | A saved choice, including an empty one, records that the user has chosen; a user who never chose is distinct from one who chose none. Turning preferences off for a view never changes the saved set. |
| `RAT-001` | Every score of a rating, a direct value or a platform's, is an integer 1–10. |
| `RAT-002` | At most one active rating exists per user/game. |
| `RAT-003` | Create requires authentication. |
| `RAT-004` | Only the owner updates/deletes. |
| `RAT-005` | Create/update require eligibility; owner delete does not. |
| `RAT-006` | Update changes the existing active rating. |
| `RAT-007` | Delete removes personal and aggregate contribution. |
| `RAT-008` | Invalid/failed operations preserve prior valid state. |
| `RAT-009` | Personal and aggregate ratings are distinct. |
| `RAT-010` | Rating identity is `UserId + GameId`, not a surrogate. |
| `RAT-011` | A rating names each played platform at most once, at most 50 of them, each a taxonomy platform; played platforms and their scores belong to the rating and are removed with it. |
| `RAT-012` | A rating has exactly one score source, a direct value or at least one scored platform, and contributes exactly one vote: the direct value or the mean of its scored platforms. |
| `RAT-013` | A newly named platform is one of the game's current release platforms; a platform already on the rating stays valid when catalogue evidence changes, and no platform is inferred. |
| `AGG-001` | Statistics use only active valid personal ratings. |
| `AGG-002` | Count equals the distribution-bucket sum; each rating sits in the bucket of its contribution rounded half up. |
| `AGG-003` | Count zero has no numeric mean. |
| `AGG-004` | Mean is the unweighted arithmetic mean of one unrounded contribution per rating. |
| `AGG-005` | Exposed mean is half-up to one decimal. |
| `AGG-006` | A platform's reading averages only explicit scores for that platform and counts users who gave one; platform counts are never voters. |
| `USR-001` | `Mis puntuaciones` exposes only the authenticated user's ratings. |
| `USR-002` | User scoping precedes personal search/sort/pagination. |

Physical deletion/audit, aggregate materialization, operational stale thresholds, and
any administrative interface remain implementation or later product decisions. The
import policy itself is a product rule; where it currently draws the line between an
independent work and a variant is owned by
[the use cases](../application/mvp-use-cases.md).
