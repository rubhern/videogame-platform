# External quality signals for catalogue discovery

- **Status:** Owner-approved research decisions; #255 phase 1 (acquisition and storage) implemented, awaiting review; presentation pending
- **Evidence date:** 2026-10-14
- **Decision owner:** Ruben Hernandez; approved 2026-10-14
- **Consumer:** Owner and implementer of [#255](https://github.com/rubhern/videogame-platform/issues/255)

## Approved source decisions

IGDB's player score is a viable complement during Gameómetro's community cold start.
Use the existing acquisition path; keep local community and external sentiment
separate. The owner approved the source and presentation direction. #255 phase 1
acquires and stores the adopted player pair; no API or presentation exists yet.
The [Product Brief](../product/product-brief.md#trustworthy-quality-and-community-direction)
owns the approved product rules; this record retains the spike's evidence, source
decisions, the original technical proposal and the
[actual-catalogue measurement](#actual-catalogue-coverage-255-phase-1).

| Candidate | Owner decision | Reason and permitted next step |
|---|---|---|
| IGDB | **ADOPT**, player score only | Useful measured coverage; no second integration. First implementation targets current private non-commercial operation. Public use remains conditional on the rights gate below. |
| IGDB critic aggregate | **DEFER** | Small samples and insufficient underlying-review provenance for a default discovery fallback. Revisit only if provenance/coverage improves. |
| IGDB combined score/count | **REJECT** | Blends different populations; neither Gameómetro sentiment nor a transparent player signal. Do not ingest/serve these in #255. |
| OpenCritic | **DEFER** | Potentially useful criticism, but additional benefit and an authorized, zero-cost storage/display contract are unproven. No integration or subscription. |

Product approval does not authorize public exposure or monetization. Those
authorizations remain pending in #274.
Public non-commercial adoption is **DEFER** pending release/rights acceptance;
commercial/ad-supported adoption is **DEFER** pending a suitable provider agreement.

## Official semantics and limits

The current [IGDB Game reference](https://api-docs.igdb.com/#game) distinguishes:

| Fields | Documented meaning | Proposed interpretation |
|---|---|---|
| `rating`, `rating_count` | IGDB users' average and number of ratings | External community sentiment; not verified purchasers or Gameómetro users |
| `aggregated_rating`, `aggregated_rating_count` | External critics' aggregate and number of scores | IGDB-sourced criticism; count is not demonstrably unique reviews/outlets |
| `total_rating`, `total_rating_count` | Combined users and critics, with combined count | Exclude from product data |
| `updated_at` | Game record modification time | Never claim it is the score's update time |

The reference does not specify critic outlets, platform/language breakdown,
deduplication, weighting/moderation, score-specific timestamps or refresh SLA.
Do not attribute the critic aggregate to Metacritic or OpenCritic, infer a review
population, or promise Spanish-player representativeness. User-score scale
normalization to 0–100 is an adapter proposal consistent with measured API values;
the field table itself supplies no explicit range/normalization formula.

## Authenticated sample

[Normalized measurements](external-quality-signals-sample.csv) retain missing values,
zero counts, identities and provenance. Scores are rounded to six decimals for
research; raw captures remain in ignored `.poc/quality-signals/`, not tracked files.

Capture: 2026-10-14 **00:18:17–00:20:09 UTC**, against the live IGDB v4 API.
Reused the [IGDB exploration](../../backend/postman/igdb-exploration.md) query/authentication
conventions and existing privately configured credentials. A disposable Python
standard-library client queried only extra scalar evidence; neither the production
adapter nor the [historical PoC](../../tools/igdb-poc/README.md) needed modification.
Nine sequential Game queries, at least 500 ms apart, at most 50 root rows/query,
30-second request timeouts, no retries; three app-token grants, no failed requests.
No application/database mutation, new dependency or provider load test occurred.

Six purposive groups of eight cover different ages, popularity, low-volume games,
potential disagreement, upcoming records and original/remake identities. This is
diversity evidence, **not a random sample or catalogue-wide coverage estimate**.
The disagreement group is a selection hypothesis, not proof of controversy.
#255 measured a representative sample of the actual catalogue
[below](#actual-catalogue-coverage-255-phase-1); these spike percentages must not be
extrapolated to the complete catalogue.

A usable pair requires a resolved identity, finite score in the proposed 0–100
range and positive integer count. An unresolved work stays outside coverage
denominators; an absent pair never becomes zero. All percentages below use resolved
identities, before applying Catalogue's import policy.

| Group | Planned / resolved | Usable users | Usable critics | Neither usable |
|---|---:|---:|---:|---:|
| Historical | 8 / 8 | 8 | 5 | 0 |
| Recent high-profile (2025–2026) | 8 / 7 | 7 | 7 | 0 |
| Niche / low-volume | 8 / 8 | 7 | 6 | 1 |
| Disagreement candidates | 8 / 8 | 8 | 8 | 0 |
| Original / remake / remaster | 8 / 8 | 8 | 8 | 0 |
| Upcoming provider records | 8 / 8 | 0 | 0 | 8 |
| **Total** | **48 / 47** | **38 (80.9%)** | **34 (72.3%)** | **9** |

Among the 39 records whose provider first-release date has passed, player coverage
is 38/39 (97.4%) and critic coverage 34/39 (87.2%). These dates are provider evidence,
not independent verification of commercial release/eligibility. Upcoming selection
includes Alpha/Early Access statuses; do not infer launch precision from a timestamp.
UFO 50 is classified as a Bundle, excluded by the current import policy: restricting
to supported work types yields 46 records, 37 usable user pairs and 33 critic pairs.

- User counts: minimum **9**, median **458.5**, maximum **5,493**. Critic counts:
  minimum **1**, median **6**, maximum **38**; 12/34 have fewer than five scores,
  23/34 fewer than ten. Counts do not prove independence or statistical confidence.
- Doom (1993, IGDB 673) returns a critic score of **80 with count 0**: a populated
  number alone is unusable. No partially missing score/count pairs were observed.
- Resident Evil 2 (1998): users **83.67 / 604**, critics **55 / 2**; its 2019 remake
  has users **87.40 / 1,434**, critics **92.83 / 12**. Never transfer between them.
- In all **34** usable dual-score records, `total_rating` matches the unweighted
  mean of the two group scores and its count the sum of their counts. Portal 2 has
  4,508 user ratings versus nine critic scores, yet the groups receive equal weight
  in that observed blend. This is a snapshot observation, not a documented formula.
- Slug guesses selected a Doom port and the expanded Final Fantasy VII record.
  Title/type/year checks corrected them to 673 and 393025; the latter has only nine
  user ratings. Demon’s Souls similarly has separate original/remake IDs. Crimson
  Desert search returned renamed/edition candidates: identity remains unresolved,
  not “no scores”. The Roottrees Are Dead row is the 2023 original, not its remake.

### Reproduction and freshness limitations

In the existing Postman Game request, select the resolved IDs in the CSV (batches
of at most 50), preserving their work identities:

```text
fields id,name,slug,url,game_type.type,game_status.status,version_parent,
first_release_date,updated_at,rating,rating_count,aggregated_rating,
aggregated_rating_count,total_rating,total_rating_count;
where id = (<CSV provider_game_id values>); sort id asc; limit 50;
```

For the frozen upcoming selection, the same fields were queried with:

```text
where first_release_date >= 1792022400 & first_release_date < 1807574400
& game_type.type = ("Main Game","Remake","Remaster","Standalone Expansion");
sort id asc; limit 8;
```

An initial 40-slug query preceded six title searches (limit 10) and a bounded exact
Doom 1993 lookup. Use the final CSV IDs for replication; slugs/titles are mutable.
There was no longitudinal recapture: update frequency and time until reviews appear
are **unmeasured**. Recent `updated_at` values do not resolve that uncertainty.

## Rights and public/commercial use

The [IGDB business FAQ](https://api-docs.igdb.com/#business-related-faq) permits local
storage/serving, describes free access, and routes monetized products through a
partnership with visible, static attribution. Public visibility alone is not stated
to require a paid tier. Ads/affiliate revenue need that commercial review; “free”
does not prove a Gameómetro agreement. The
[Twitch agreement](https://legal.twitch.com/en/legal/developer-agreement/), last modified
12/04/2024 as displayed, restricts commercial data sharing without written permission
(VI.B), requires lawful third-party use (IX), and deletion on termination/request
(VI.D/VIII). Section I allows a superseding agreement.

**Unresolved conflict:** IGDB FAQ permits keeping acquired data after partnership
termination; Twitch requires deletion. Do not choose the more convenient text or
claim perpetual retention. Before public score exposure, the owner must record which
agreement governs score storage/display, retention/removal and attribution; obtain
provider clarification if the applicable terms cannot resolve the conflict. No
provider contact was sent. The original investigation approved plain linked “Jugadores IGDB” attribution.
The owner later requested the official mark for discovery (#255 phase 2); its
verified source, limited attribution permission and local asset handling are owned
by the [provider asset record](../../frontend/public/assets/providers/README.md).
This does not resolve the score-retention conflict or grant public/commercial exposure.

This score-specific gate feeds [#274](https://github.com/rubhern/videogame-platform/issues/274)
and the [canonical public-release gates](../development/delivery-lifecycle.md#public-production-gates).
It does not reopen hosting or authorize spending. No review text, personal reviewer
data, raw dump, image rights or unlimited redistribution is part of this proposal.

## OpenCritic: bounded alternative assessment

Low IGDB critic counts justify a limited feasibility check: broader attributable
criticism could add value. That incremental benefit is **unmeasured**, and does not
replace the player-sentiment objective.

The official [FAQ](https://opencritic.com/faq) distinguishes Top Critic Average and
recommendation percentage; their populations/count denominators must not be treated
as interchangeable. Its rendered accordion did not expose calculation details in
this research client. The [RapidAPI listing](https://rapidapi.com/opencritic-opencritic-default/api/opencritic-api)
identifies an OpenCritic API, but accessible provider/marketplace pages did not verify
a current reader license, usable fields/counts, storage rights, branding, quotas,
zero-cost/overage protection, commercial rights or refresh guarantees. `/api` on
OpenCritic returned 404. No account, key or subscription was created.

OpenCritic's footer links [Valnet terms](https://www.valnetinc.com/en/terms-of-use):
sections 3/5 limit site use and reproduction; that is not an API storage/display
contract. No website scrape or undocumented endpoint is a fallback. **DEFER** until
an authorized zero-cost pilot can compare the same work identities and prove useful
coverage beyond IGDB. Matching would require reviewed typed external references,
title/year/type/platform checks and unresolved collisions, never title-only merging.

## Provider-independent representation proposed for #255

Follow the [solution boundaries](../architecture/mvp-solution-architecture.md#modules-and-ownership)
and [ADR-0017](../decisions/0017-discover-catalogue-members-automatically-from-igdb.md).
Catalogue owns acquired external evidence; Ratings alone owns Gameómetro statistics.
No new module, provider framework or cross-module table access is needed.

| Conceptual current PostgreSQL row / application value | Purpose |
|---|---|
| Internal `GameId`, source key, signal kind (`external_community`) | Unique `(game, source, kind)`; source is a bounded approved vocabulary, not a transport DTO |
| Decimal score on declared 0–100 scale, positive contributing count | Database range/count checks; no mean/count fabrication or integer truncation |
| Source label/link, observed time, optional provider-record update time | Explicit provenance; distinguish acquisition from unknown score update time |

Use a small Catalogue-owned current-state relation with a foreign key to internal
Game identity. Provider IDs remain in the existing external-reference mechanism.
Initially one source/kind means at most one score row/game; no history ledger or
generalized metric store. Extend only for an adopted second signal. Application
contracts express these concepts, not `IgdbGamePayload` field names.

Acquire the two user fields in the existing bounded Game fetch, with no extra
per-game external request. In the per-Game fenced transaction, a complete successful
absence clears the previous source-owned signal; zero count means no usable score.
Malformed/out-of-range/partial pairs or fetch failure preserve the last valid Game
state and never advance a successful score-check time. Rechecks of unchanged data
record observation separately from content change. Keep evidence not checked recently
distinguishable from a current absence or known incorrect data; never silently
reuse a score as newly checked or suppress it solely because of age.

The #155 scheduled policies only revisit games selected by their release windows;
see the executable [configuration](../../backend/src/main/resources/application.yaml).
They are sufficient for those games after field acquisition, not perpetual historical
score freshness. #246 would hydrate scores as its chunks reuse UC-009 **after #255**,
but does not promise continuous refresh of all old games. No new scheduler or
score expiry is introduced. Preserve observation time for traceability; age alone
does not invalidate the last valid score or justify hiding it.

Read the selected release page first, then a bounded score batch and a Ratings-owned
batch summary through application contracts, composed by delivery. The current
single-game Ratings read must not be looped per card. PostgreSQL filters/orders/pages
with the existing unique tie-breaker; score enrichment changes neither order nor
page membership. Memory/returned rows are O(page size), with one score per game and
fixed query count. No `DISTINCT` to hide fan-out, provider call, new cache or process-local
correctness state. Validators/cache lifetime must reflect score and Ratings changes;
HTTP cache lifetime must not become a score-validity or display-expiry rule. Personal
ratings remain outside shared-cacheable discovery. SQL plans and #85 cost evidence
belong to implementation; none is claimed for this unimplemented model.

## Approved presentation and implementation handoff

The owner rejected vote-count thresholds and automatic score expiry. Follow the
[canonical Product Brief policy](../product/product-brief.md#trustworthy-quality-and-community-direction):
each available valid score/count pair remains visible and separately attributed,
including one vote; observation age does not hide it. Missing/invalid evidence and
unavailable local statistics remain distinct from zero ratings. Do not use release
eligibility for submitting Gameómetro ratings as a new external-score display gate;
a work-level score does not establish platform-specific sentiment.

The /10 external display conversion remains a technical proposal: divide the
explicit /100 value by ten and round half-up to one decimal, retaining source and
exact count. It never changes the Gameómetro aggregate or combines populations.
Final API and visual delivery follow their canonical owners during #255.

#255 is prepared to implement the approved product decisions, including measurement
of the actual catalogue; public/commercial authorization remains #274's independent
gate. The PostgreSQL/application representation above was the spike's proposal. #255
phase 1 implemented it as Catalogue-owned current state; the
[domain model](../architecture/domain/mvp-domain-model.md) (`XSC-001`–`XSC-004`),
[use cases](../architecture/application/mvp-use-cases.md) and Flyway SQL now own the
implemented behaviour and schema. OpenAPI changes first when a wire contract follows.

Documentation/evidence only: no executable artefact version bump. Local validation
is link/ownership validation plus CSV measurement reconciliation; runtime, SQL-plan,
browser and integration checks are deferred to #255's affected-area gates.

## Actual catalogue coverage (#255 phase 1)

Measured on **2026-10-14, 10:19–10:33 UTC** with the #255 phase-1 backend
(`0.31.0-SNAPSHOT`, working tree under review) against live IGDB v4, using owner-approved
private credentials passed to a disposable container without being read. The target was
a disposable copy of the owner's local PostgreSQL catalogue (last real synchronization
2026-10-04); the original volume was only read. The new migration applied on top of
that real Flyway history. All provider work went through existing UC-009 paths: two runs
of the normal date-window command and the existing private current-release repair
command, one known Game per call. No new import path, provider query or backfill was
used. About 950 provider requests were made at the configured 3 requests/s, without
retries or provider failures.

**Frame and selection.** The frame is every accepted Game with a typed IGDB Game
reference, frozen in PostgreSQL before any acquisition: **20,241 Games**, none excluded
for missing identity. Seven disjoint strata use stored provider first-release evidence
(Madrid date 2026-10-14) and, for remakes/remasters, a title heuristic (`remake`,
`remaster(ed)`, `definitive edition`, `reloaded`, `redux`, `HD`), because work type is
not stored. That heuristic also catches editions and parody titles. Within each stratum,
PostgreSQL ordered Games by `md5(game_id || 'issue-255-2026-10-14')` and kept a fixed
count. Every sampled Game was then refreshed once. [Per-Game results](external-quality-signals-catalogue-sample.csv)
keep stratum, provider identity, stored first-release evidence, state, pair and check time.

| Stratum (first release) | Frame | Sampled | Usable pair | Stated absence | Usable % | Count min / median / max |
|---|---:|---:|---:|---:|---:|---|
| Before 2020 | 197 | 30 | 14 | 16 | 46.7 | 2 / 59.5 / 1,829 |
| 2020–2024 | 602 | 30 | 12 | 18 | 40.0 | 5 / 11.5 / 686 |
| 2025-01-01 to 2026-09-13 | 14,894 | 60 | 2 | 58 | 3.3 | 5 / 5 / 5 |
| Last 30 days | 1,259 | 40 | 1 | 39 | 2.5 | 12 |
| Upcoming | 2,657 | 40 | 0 | 40 | 0.0 | — |
| Unknown first release | 553 | 20 | 0 | 20 | 0.0 | — |
| Remake/remaster title | 79 | 30 | 4 | 26 | 13.3 | 7 / 65.5 / 500 |
| **Total** | **20,241** | **250** | **33** | **217** | — | 2 / 14 / 1,829 |

The stratum-weighted estimate of Games holding a usable IGDB player pair is **4.3%**
(standard error 1.7 percentage points; a normal approximation that is rough at such
low proportions). The catalogue is dominated by 2025–2026 releases acquired through
date windows, most of them without IGDB player ratings; older works are much better
covered but are a small part of the catalogue. All 250 sampled Games resolved and
returned a valid answer: no identity exclusion, no invalid, partial or out-of-range
pair (`player_score_invalid` stayed zero) and no unchecked Game. Among the 33 usable
pairs, 4 have fewer than five ratings and 14 fewer than ten. None has exactly one,
although one-rating scores remain valid and visible by policy.

**Real acquisition checks.** A normal window run for 2026-10-10..13 committed 95 Games
(23 created, 39 updated, 33 unchanged, 5 deferred by import policy, none failed):
3 usable pairs and 92 stated absences. Repeating it changed nothing. All 95 score rows
advanced their check time while value, count, attribution and change time stayed
identical, and the catalogue revision did not rotate. Attribution URLs pointed to each
work's own IGDB page. The 13 #256 sample Games present in the catalogue were refreshed as
named controls, outside the denominators. The 5 stated absences and 5 of 8 usable pairs
matched the 00:18 UTC capture exactly; Resident Evil 2 (2019) and Resident Evil Requiem
had each gained one rating, and Starfield's mean moved slightly with an unchanged count.
In the catalogue, Fatal Frame II: Crimson Butterfly Remake (80.0 / 7) and its separate
Digital Deluxe Edition work (stated absence) kept separate scores. None of the originals
of the sampled remakes is in the catalogue, so original/remake separation rests on the
automated PostgreSQL tests.

**Reach of current synchronization.** With the default scheduled windows (28 days back
and forward daily, 180 days ahead weekly), about **1,966 frame Games (9.7%)** had a
stored day- or month-precision release inside a window on 2026-10-14. This is an
approximation: windows move daily, and quarter, year and unknown dates were not counted.
The remaining Games gain a score only through a manual interval containing one of their
releases, the existing operator repair of all known Games (two provider requests per Game)
or the future #246 hydration. Until then they have no score state, which differs from a
stated absence.

**Limitations.** This is one dated snapshot of one local catalogue and one provider
state, not a longitudinal freshness measurement. The 13 controls show that counts change
within hours. Stratum sizes give coarse per-stratum precision. The remake stratum is
heuristic. The "last 30 days" and "upcoming" strata depend on stored provider
first-release evidence, not verified commercial release. The measurement says nothing
about the public, commercial or rights suitability tracked in #274. A wall-clock step
backwards on the WSL2 host made eleven repair calls fail when recording their run
(`synchronization_run` requires completion at or after start). Their Game writes had
already committed. The scratch runs were marked abandoned and those Games replayed
idempotently. This affects only the operational run record, not score evidence.
