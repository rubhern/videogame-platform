# MVP close-out architecture review

- **Type:** Point-in-time architecture review (evidence, not an approved decision)
- **Reviewer:** AI-assisted review pass; the owner retains approval authority
- **Revision reviewed:** `d10bc44` on `main`, clean working tree
- **Scope:** module boundaries and ownership, hexagonal layering, ports and internal
  contracts, Spring Modulith structure, OpenAPI/BFF boundary, identity, persistence
  ownership, consistency and transactions, IGDB integration and local serving,
  scalability of the current design, resilience, and evolution capacity.
- **Out of scope:** code-level quality (see the
  [code review](code-review.md)), infrastructure, delivery, and observability
  mechanics.
- **Baseline contrasted:** Product Brief and story map, domain model, use cases,
  solution architecture, technology baseline, OpenAPI, platform and delivery design,
  ADR-0001 to ADR-0019, `AGENTS.md`, and the `.claude/rules/hexagonal-boundaries.md`
  constraint.
- **Method:** every claim below was checked against the cited source file at the
  reviewed revision; no test suite was run for this document. Scalability follows
  the `scalability-by-design` review workflow (characterize scale, trace the hot
  path, bound work, push work to PostgreSQL, total ordering, integrity, caching,
  horizontal scaling, speculative-infrastructure rejection).

Nothing here changes code, ADRs, or canonical documentation. Where implementation
and documentation disagree, the disagreement is stated and the canonical owner is
named; no interpretation is chosen silently.

---

## 1. Executive summary

The architecture fits the product it serves. One deployable modular monolith with
five Spring Modulith modules, one PostgreSQL database with logically owned schemas,
a same-origin BFF with server-side OIDC, a contract-first HTTP API, and a bounded
operator-driven IGDB synchronization that never touches a user request. Every one of
those choices is recorded in an accepted ADR, and the implementation follows the
records closely: the hexagonal rules are enforced by ArchUnit and Modulith tests,
every read is bounded and ordered in SQL, and provider types never leave their
adapter. **No `HIGH` finding was identified.** Nothing compromises a boundary,
correctness, security, or consistency in a way that must be fixed before the MVP
closes.

What the review did find is a small number of places where the implementation is
*wider* or *less explicit* than the approved records, and one policy interaction
that will become visible to users soon after real synchronization starts:

1. **Three seams are wider or less explicit than documented.** Ratings evaluates
   eligibility from the complete `GameDetailsResult` (aliases, summary, resolved
   cover) although the solution architecture promises a "narrow Catalogue application
   context" (`AR-01`); a JSON read under `/auth` is consumed by the SPA outside the
   OpenAPI contract (`AR-02`); and a platform readiness probe queries a Catalogue
   table by name, which the architecture's strongest rule forbids and no fitness
   function detects (`AR-03`). A cross-schema foreign key from `ratings.rating` to
   `catalogue.game` is a real inter-module dependency that no document mentions
   (`AR-04`).
2. **Two consequences of ADR-0017 and ADR-0018 deserve an explicit decision.** The
   Ratings listing projection is refreshed for every synchronized game, so it
   mirrors the whole catalogue rather than the rated subset (`AR-05`), and
   interval-scoped synchronization combined with a seven-day per-release freshness
   threshold will mark most historical releases "stale" permanently (`AR-06`).
3. **Two evolution risks are cheap to record now and expensive to discover later.**
   Product identity is a hash of `issuer + subject` with no mapping table, so a
   Keycloak issuer URL change silently orphans every rating (`AR-07`); and the
   `publication` axis retained by ADR-0017 is now a constant that still shapes every
   key and query (`AR-08`).

Findings: **0 HIGH, 8 MEDIUM, 4 LOW**, plus eight recorded revisit triggers that are
not current work. Seven GitHub issues are suggested; two of them are documentation
decisions rather than code.

---

## 2. Overall architecture assessment

**Coherence.** The five modules (`catalogue`, `ratings`, `identity`, `api`,
`platform`) match the ownership table in the solution architecture exactly, and
`ModularityTest` pins that set. Named interfaces are narrow and deliberate:
`catalogue` exports `application`, `cover`, `releases`, `search`, `details`, and
`synchronization`; `ratings` exports `application`; `identity` exports
`application`. `api` and `ratings` declare `allowedDependencies` rather than
inheriting everything, so a new cross-module import is a build failure, not a review
comment. The domain and application packages contain no Spring, Jakarta, JDBC,
generated, or provider types, and `HexagonalArchitectureTest` proves it.

**Simplicity.** The system does not carry abstractions it does not use. There is no
aggregate rehydration for reads (query-specific read ports per ADR-0015/0016), no
event bus beyond one synchronous in-process Spring event, no repository pattern over
JDBC, and no second datastore. The synchronization path is deliberately a single
operator command with bounded internal paging. The frontend is a plain SPA behind a
BFF. Where the design does add a structure (the Ratings listing projection, the
`catalogue_publication` singleton), an ADR explains why.

**Maintainability.** Boundaries are the right size for one person: each module has
one composition root, ports are records with compact-constructor invariants, and the
policies that encode product rules (`ReleaseStatusPolicy`, `GameImportPolicy`,
`ReleaseReconciliationPolicy`, `RatingEligibilityPolicy`) are pure and tested.
The cost is that some rules must be restated where a module cannot see another's
domain (`RatingBoundary` in identity, freshness/status mirrors in application
results); that is the accepted price of closed Modulith modules and is not
recommended for change.

**Evolution capacity.** The plausible next steps of the product (a scheduled
synchronization, a second taxonomy value, more games, more ratings, a second user)
are all absorbed by the current design without structural change. The steps that
would require structural change (a second provider, a second identity issuer,
multi-instance deployment, a public release) all have a recorded trigger in an ADR,
except identity continuity (`AR-07`), which this review adds.

**Documentation versus implementation.** The records are unusually current. The
divergences found are listed in section 4 with the canonical owner named for each;
none is a case of the documentation describing a different system, and two are
cases of the implementation being wider than the record rather than contradicting
it.

---

## 3. Current architecture strengths

Worth naming explicitly so that they are protected, not merely preserved by
inertia:

- **Provider isolation is real, not aspirational.** `IgdbCatalogueProviderAdapter`
  emits only product vocabulary (`ProviderWork`, `ProviderRelease`, taxonomy codes,
  closed date variants, `ProviderMappingFailure`). No IGDB identifier, taxonomy
  name, payload, or URL crosses the port, and `ProviderBoundaryTest` prevents
  regression. The user request path has no compile-time or runtime reach to the
  provider client; the only provider-derived code on the read path is the cover
  URL resolver required by ADR-0001.
- **Bounded work by construction.** `UC-001`, `UC-002`, `UC-003`, and `UC-008` all
  select, count, order, and page in PostgreSQL with a unique final tie-breaker, run in
  a read-only `REPEATABLE READ` transaction with a statement timeout, and hard-bound
  what crosses into Java (`pageSize`, `pageSize × releaseContextLimit`,
  `MAX_RELEASES`/`MAX_ALIASES` with sentinel rows). Synchronization memory is
  `O(providerPageSize + maxReleasesPerGame)` and its total work grows only with the
  requested interval. The scalability skill's prohibited patterns do not occur.
- **Integrity is in the database.** Closed-value checks, the date-precision
  coherence check, `UNIQUE NULLS NOT DISTINCT` on the release tuple, one current
  publication, one active synchronization run per provider with heartbeat fencing,
  rating identity as `(user_id, game_id)`, and conditional single-row writes with an
  opaque version token. Correctness never depends on process-local state; the only
  process-local mechanism (the IGDB rate limiter) is politeness, and the database
  single-run invariant makes it safe.
- **Per-Game atomicity in synchronization** (ADR-0017) is the right unit: a failed
  Game rolls back alone, earlier and later Games survive, identical values do not
  rewrite rows, and verified human evidence is never overwritten by provider evidence.
- **The BFF boundary is complete.** Confidential client, PKCE, nonce, disabled
  request cache, opaque `HttpOnly` cookie, session-bound CSRF exposed only to
  authenticated sessions, origin and `Sec-Fetch-Site` checks before the CSRF filter,
  allowlisted redirects, single-use replay-safe return context. Product identity is
  derived server-side from validated `issuer + subject` and nothing else.
- **Contract-first is enforced at compile time.** Controllers implement generated
  interfaces; generated types are confined to `api.delivery` by ArchUnit; the
  frontend consumes generated `paths` types through a product-facing layer; contract
  conformance tests validate responses against the reviewed source.
- **Failure behaviour is modelled, not improvised.** `CATALOGUE_NOT_READY`,
  `CATALOGUE_READ_FAILED`, an explicit unavailable aggregate on the game page,
  fallback covers, provider failures recorded per run, and readiness that does not
  depend on IGDB or telemetry.

---

## 4. Findings

Priority meaning: `HIGH` = compromises a boundary, correctness, security,
consistency, important maintainability, or reasonable evolution; `MEDIUM` = a real
architectural improvement with an observable benefit; `LOW` = worth recording or
doing opportunistically. Type: `CURRENT_PROBLEM` exists today; `IMPROVEMENT` is a
better fit for the current product; `REVISIT_TRIGGER` is not current work.

### Documentation versus implementation register

| Topic | Implementation | Record | Canonical owner | Finding |
|---|---|---|---|---|
| Eligibility input | Complete `GameDetailsResult` | "narrow Catalogue application context" | Solution architecture | `AR-01` |
| `/auth/rating-intent` read | JSON body parsed by the SPA | "Authentication navigation under `/auth` … not exposed here" (OpenAPI) versus "HTTP/JSON BFF boundary described contract-first" (ADR-0003, `AGENTS.md`) | ADR-0003 for the policy; OpenAPI for the operation list; the two are narrower/wider than each other | `AR-02` |
| Cross-module tables | `platform` readiness probe selects from `catalogue.catalogue_publication` | Rule 5: "never another module's repository/table" | Solution architecture | `AR-03` |
| Cross-schema foreign key | `ratings.rating.game_id REFERENCES catalogue.game` | Not mentioned anywhere | Solution architecture ("Data and consistency") should own it | `AR-04` |
| Multi-instance readiness | HTTP session, CSRF, OAuth client, and return context in the servlet session | "advisory locks serialize refreshes across application instances" (solution architecture) versus "horizontal scaling requires a shared or otherwise replay-safe session design" (ADR-0003) | ADR-0003 states the limit; the solution-architecture sentence implies a readiness the identity layer does not have | Revisit trigger `RT-2` |
| Stable code vocabulary | `RELEASE_DATE_INVALID`, `expired`/`cancelled`/`invalid` markers, `SYNCHRONIZATION_*` strings | `RELEASE_DATA_INVALID`, `RETURN_CONTEXT_EXPIRED`, `AUTHENTICATION_CANCELLED`, `PROVIDER_MAPPING_FAILED`, `EXTERNAL_REFERENCE_CONFLICT` | Use-case record owns "stable errors" | `AR-09` |
| Taxonomy identifier shape | UUID on `/releases`, code on `/games/{gameId}` | "Opaque, provider-independent product identifier" | OpenAPI | Code review `CR-01` (not repeated here) |

### MEDIUM

#### `AR-01` — Ratings evaluates eligibility from the complete game details contract

- **Priority:** MEDIUM · **Type:** IMPROVEMENT · **Area:** `ratings` ↔ `catalogue` contract
- **Evidence:** `ratings/application/internal/PersonalRatingService.java:79-84`
  (`requireEligibility` calls `GetGameDetailsUseCase.get`);
  `ratings/application/internal/RatingContextService.java:31-48` (builds
  `RatingEligibilityPolicy.Evidence` from `BrowseReleasesResult.Release` fields);
  `catalogue/application/details/internal/GameDetailsService.java` (loads aliases,
  summary, all releases, and resolves the cover through `CatalogueCoverPolicy`);
  `docs/architecture/mvp-solution-architecture.md` ("Ratings evaluates release
  eligibility from a narrow Catalogue application context").
- **Problem:** the rating write path needs six booleans and one date per release; it
  receives aliases, summary, resolved cover, and the release evidence typed as the
  *browse* result (`BrowseReleasesResult.Review`, `.Verification`). Two consequences
  follow. First, a rating command inherits failure modes it does not need: an invalid
  stored cover reference makes `IgdbCoverReferenceResolver` throw
  `CatalogueDataInvalidException`, so `PUT /me/ratings/{gameId}` returns `500`
  although eligibility does not depend on covers. Second, `ratings` depends on the
  `catalogue::releases` named interface only to reuse enums named after UC-001, which
  is naming coupling rather than a real dependency on release browsing.
- **Impact:** a wider-than-documented contract that couples the private write path to
  the public page's data needs; a change to the game page (a new summary kind, a
  richer cover) is a change to the rating command's dependency surface.
- **Proposal:** a `GetGameReleaseEvidenceUseCase` (or a `releaseEvidence(gameId)`
  method on the existing `details` interface) returning `List<ReleaseEvidence>` plus
  `evaluatedOn`, with `ReleaseEvidence` in `catalogue.application.details` carrying
  exactly the status, review, verification, date-precision, and period-end fields
  eligibility needs. `RatingContextService.get` and `PersonalRatingService.requireEligibility`
  consume it; `GameDetailsEndpoint` keeps calling `GetGameDetailsUseCase` for the
  page. The `ratings` module then drops `catalogue::releases` from its allowed
  dependencies.
- **Simpler alternative considered:** keep `GameDetailsResult` and make cover
  resolution lazy or non-throwing. Rejected: it fixes the symptom, keeps the wide
  contract, and the solution architecture already asks for the narrow one.
- **Trade-offs:** one more read port method and one more application result type;
  the details read query is reused (the port can serve both), so no new SQL.
- **Cost:** small–medium · **ADR:** no · **Issue:** yes

#### `AR-02` — A JSON read under `/auth` is consumed by the SPA outside the contract

- **Priority:** MEDIUM · **Type:** IMPROVEMENT · **Area:** `identity` / API boundary
- **Evidence:** `identity/adapter/web/RatingIntentController.java:87-113`
  (`GET /auth/rating-intent` returns `PendingRatingIntent` JSON);
  `frontend/src/features/session/rating-intent.ts` (hand-typed `PendingRatingIntent`,
  raw `fetch`, manual shape guard); `docs/architecture/api/openapi.yaml:11-13`
  ("Authentication navigation under `/auth` … intentionally not exposed here");
  ADR-0003 ("Use an HTTP/JSON BFF boundary described contract-first by OpenAPI");
  `AGENTS.md` ("Every product-facing backend HTTP API is contract-first").
- **Problem:** `GET /auth/rating-intent/start` is genuinely navigation (a redirect).
  `GET /auth/rating-intent` is not: it is a same-origin JSON resource the SPA fetches,
  parses, and renders, with its own 404 semantics. It is the only browser-consumed
  JSON that has no OpenAPI operation, no generated type on either side, no contract
  conformance test, and a duplicated hand-written DTO in the frontend, which the
  frontend skill explicitly prohibits ("never create parallel hand-written API DTOs").
- **Impact:** small today (three fields), but it is the seam through which the
  contract-first rule has already been bent once; the OpenAPI scope sentence and
  ADR-0003 disagree on whether it is allowed.
- **Proposal:** move the read into the contract as `GET /api/v1/session/rating-intent`
  (Session tag, `PendingRatingIntent` schema, `404` with no body preserved), keep
  `/auth/rating-intent/start` as navigation, and generate the frontend type. This is
  an `openapi-change`; the `identity` module keeps ownership because the
  `RatingReturnContextStore` stays there and `api.delivery` may depend on
  `identity::application` (add the read to that named interface or expose the store
  through it).
- **Simpler alternative considered:** leave the endpoint where it is and add one
  sentence to `api-conventions.md` recording the exception. Acceptable if the owner
  prefers zero code change; it should then also say why hand-typed frontend DTOs are
  tolerated for this route.
- **Trade-offs:** the contract grows from nine to ten operations; the identity
  module's public surface gains one read contract.
- **Cost:** small · **ADR:** no (aligns with ADR-0003) · **Issue:** yes

#### `AR-03` — A platform readiness probe queries a Catalogue table, and nothing detects SQL-level cross-module access

- **Priority:** MEDIUM · **Type:** CURRENT_PROBLEM · **Area:** `platform` ↔ `catalogue`
- **Evidence:** `platform/observability/CatalogueStoreHealthIndicator.java:14-15`
  (`SELECT EXISTS (SELECT 1 FROM catalogue.catalogue_publication LIMIT 1)`);
  `application.yaml` readiness group includes `catalogueStore`; solution architecture
  rule 5 and `.claude/rules/hexagonal-boundaries.md` ("Reach into another module's
  repository or tables").
- **Problem:** the rule is the strongest one the architecture has, and it is enforced
  only for Java dependencies. SQL text is invisible to Modulith and ArchUnit, so this
  probe (and any future one) passes every architecture test while reading another
  module's table. The probe is technically harmless, but it is exactly the pattern the
  rule exists to stop, and the previous review already noted the readiness probe
  duplicated the `db` indicator [`DEL-04`].
- **Impact:** low operational risk, high precedent risk: the next person who needs a
  cross-module read has a working example of doing it through SQL.
- **Proposal:** two small moves. (1) Replace the probe with either the standard
  `db` indicator alone or a `CatalogueReadiness` contract exported by `catalogue`
  (a one-method interface implemented by its persistence adapter and consumed by
  `platform`), keeping the schema-existence semantics if they are wanted. (2) Add an
  architecture test that scans string constants and text blocks in `src/main` for
  `catalogue.` / `ratings.` schema-qualified table names and asserts they appear only
  under the owning module's `adapter.persistence` packages (and `db/migration`). It
  is a regex over class files, not a parser, and it would have caught this.
- **Simpler alternative considered:** delete the indicator and rely on `db`. Fine if
  schema presence is not a readiness requirement; the platform design says readiness
  "proves supported local-data behaviour", which argues for keeping a catalogue-owned
  probe.
- **Trade-offs:** one more tiny contract; one more fitness function to maintain.
- **Cost:** small · **ADR:** no · **Issue:** yes

#### `AR-04` — An undocumented cross-schema foreign key couples Ratings to Catalogue physically

- **Priority:** MEDIUM · **Type:** CURRENT_PROBLEM (documentation gap; the constraint itself may be right) · **Area:** `ratings` ↔ `catalogue` data
- **Evidence:** `db/migration/V20260906_120000__support_public_game_details.sql:32`
  (`game_id uuid NOT NULL REFERENCES catalogue.game(game_id)`); ADR-0002 ("give each
  module logical ownership of its tables and prevent bypassing module boundaries
  merely because the physical database is shared"); solution architecture "Data and
  consistency" (no mention).
- **Problem:** the foreign key is a real dependency: Catalogue cannot delete a game
  while a rating exists, a future extraction of either module must break it, and it
  is enforced by the database rather than by the application contract that the
  architecture names as the only cross-module channel. It is also useful: it makes
  `RAT-010` (identity is `UserId + GameId`) reference a real product identity and it
  is the only thing preventing a rating on a nonexistent game if the application
  check were ever bypassed. Neither the benefit nor the coupling is written down, and
  `ratings.game_listing` (ADR-0018) deliberately has *no* such key, so the two
  Ratings tables follow two different policies.
- **Impact:** an implicit decision that will surprise whoever first tries to delete
  or merge a game, or to move Ratings anywhere.
- **Proposal:** decide and document, no code change recommended. The recommendation is
  to **keep** the key for the MVP (integrity beats boundary purity while one database
  is the approved topology) and add to the solution architecture "Data and
  consistency": "Ratings references `catalogue.game` identity through a foreign key;
  Catalogue never deletes a game; this is the one accepted physical cross-module
  dependency and must be removed before any data-boundary separation."
- **Simpler alternative considered:** drop the key. Rejected without a deletion use
  case; it removes integrity for no product benefit.
- **Trade-offs:** none beyond writing the sentence.
- **Cost:** small · **ADR:** no (a sentence in the solution architecture; ADR-0002
  already covers the principle) · **Issue:** yes (documentation)

#### `AR-05` — The listing projection is refreshed for every synchronized game, so it mirrors the whole catalogue

- **Priority:** MEDIUM · **Type:** IMPROVEMENT · **Area:** `ratings` projection / synchronization transaction
- **Evidence:** `ratings/adapter/persistence/JdbcGameListingProjection.java:92-94`
  (`@EventListener on(GameListingChanged)` → unconditional `refresh`);
  `catalogue/adapter/persistence/synchronization/JdbcCatalogueSynchronizationStore.java:355`
  (`listingChanged.accept(gameId)` on every `saveGame`); ADR-0018 ("Ratings duplicates
  a bounded slice of Catalogue listing data"; backfill covers "already-rated games").
- **Problem:** the projection exists so that `Mis puntuaciones` can search and sort
  the user's rated games without touching Catalogue. Its natural extent is the set of
  rated games (that is what the startup backfill targets), but the synchronization
  hook writes a row, deletes and reinserts aliases, and maintains two GIN-indexed
  `tsvector` columns for **every** game the operator synchronizes, rated or not.
  Over time `ratings.game_listing` becomes a second copy of the catalogue's title,
  alias, and cover data with its own search indexes, and every Catalogue Game
  transaction pays that write inside its own commit. Rating creation already calls
  `listing.refresh` before the first rating, so restricting the synchronization-time
  refresh to games that already have a projection row loses nothing.
- **Impact:** write amplification and storage proportional to the catalogue instead of
  to the rated set; a larger surface where a Ratings-side failure rolls back a
  Catalogue publication (accepted by ADR-0018, but currently for every game).
- **Proposal:** in `on(GameListingChanged)`, refresh only when
  `EXISTS (SELECT 1 FROM ratings.game_listing WHERE game_id = :game)` (or when a
  rating exists). The ADR-0018 guarantees hold unchanged: rated games stay
  synchronously fresh, unrated games get their row at first rating, startup backfill
  covers the gap. Add one sentence to ADR-0018's consequences stating the
  projection's extent is the rated set.
- **Simpler alternative considered:** leave as is and accept the copy. Reasonable
  only while the catalogue stays small; the fix is a one-statement guard.
- **Trade-offs:** one extra indexed existence check per synchronized game (cheaper
  than the upsert it avoids).
- **Cost:** small · **ADR:** no (note in ADR-0018) · **Issue:** yes

#### `AR-06` — Interval-scoped synchronization and a per-release freshness threshold will mark most historical releases "stale" permanently

- **Priority:** MEDIUM · **Type:** IMPROVEMENT (product hypothesis; decision needed) · **Area:** synchronization policy × freshness policy
- **Evidence:** `application.yaml` (`freshness-threshold: P7D`);
  `catalogue/application/internal/CatalogueFreshnessPolicy.java` (per-release
  `lastSynchronizedAt + threshold < now` → `STALE`); ADR-0017 ("A release moved
  completely outside a later requested interval is not refreshed …; cross-window
  maintenance is deliberately deferred"); domain model ("Freshness alone does not
  revoke a historical release fact"); `frontend/tests/packaged-game-details.spec.ts`
  and `GameDetailsApiIntegrationTest` already assert `stale` for fixtures.
- **Problem:** synchronization touches only releases inside the operator's date
  interval, and freshness is "days since this release row was last synchronized". A
  release from 2024 that the operator synchronized once will read `stale` seven days
  later and stay so unless the operator re-runs 2024 windows forever. Under normal
  operation (periodic recent/upcoming windows) the game page and the release list
  will show "Datos locales desactualizados" on nearly every released, day-precision
  release, which is exactly the population whose data cannot change. Each policy is
  individually documented; their interaction is not, and the story map lists freshness
  as an evaluation signal the user is meant to trust.
- **Impact:** a product signal that becomes noise; user-visible shortly after real
  synchronization begins.
- **Proposal:** a product decision (label it a hypothesis until the owner confirms),
  then a small change. Two candidate semantics, both inside the current design:
  (a) freshness applies only to releases whose status can still change (not
  `released` with an exact past day, not `cancelled`), so historical facts are
  reported as neither fresh nor stale; or (b) freshness is derived from the last
  synchronization run that *covered the release's date*, recorded once per run,
  rather than per row. Option (a) is a two-line policy change plus a contract note;
  option (b) needs a run-coverage table. Recommend (a).
- **Simpler alternative considered:** operational only — schedule wide historical
  windows. Rejected: it multiplies provider work to refresh facts that do not change
  and ADR-0017 defers cross-window maintenance on purpose.
- **Trade-offs:** (a) changes the meaning of `freshnessStatus` for released items;
  the OpenAPI description and the domain model's freshness sentence must say so.
- **Cost:** small once decided · **ADR:** no unless the owner changes the freshness
  concept itself; then a short amendment to the domain model and ADR-0017
  consequences · **Issue:** yes (decision first)

#### `AR-07` — Product identity is a hash of `issuer + subject` with no mapping, so an issuer change orphans every rating

- **Priority:** MEDIUM · **Type:** IMPROVEMENT (record now; act only on the trigger) · **Area:** `identity` continuity
- **Evidence:** `identity/domain/UserId.java:31-38`
  (`UUID.nameUUIDFromBytes(issuer + '\n' + subject)`);
  `IdentitySecurityConfiguration.java` (`OIDC_ISSUER_URI` from configuration);
  ADR-0007 ("Link a product user to stable `(issuer, subject)` identity"; reconsider
  on "another provider"); domain model ("accounts are outside the domain"); ADR-0019
  (the private host already moved once).
- **Problem:** the derivation is deterministic and provider-independent in *shape*
  but not in *value*: the issuer URL is part of the input. Renaming the Keycloak
  host, switching `http` to `https`, renaming the realm, or moving to another
  provider changes every `UserId`, and `ratings.rating.user_id` has no way back to
  the person. The design is correct for the MVP's stated scope (no accounts), but
  the failure is silent and total, and the project has already changed hosting once.
- **Impact:** none today; potential loss of all personal data on the first identity
  topology change.
- **Proposal:** no table now. Record the dependency and the recovery recipe: in
  ADR-0007 consequences, state that the issuer URL is part of product identity and
  that an issuer change requires rewriting `ratings.rating.user_id` with a one-off
  migration that recomputes the v3 UUID from `(new issuer, subject)`; keep a tested
  SQL or Java recipe alongside `DatabaseMigrationApplication`. Introduce an
  identity-owned `(user_id, issuer, subject)` mapping only if a second issuer is
  ever approved (that is the ADR-0007 trigger).
- **Simpler alternative considered:** hash the subject alone. Rejected: subjects are
  only unique per issuer, and ADR-0007 chose the pair deliberately.
- **Trade-offs:** none now; the recipe is documentation plus one reusable test.
- **Cost:** small · **ADR:** amendment to ADR-0007 consequences (no new ADR) · **Issue:** yes

#### `AR-08` — The `publication` axis retained by ADR-0017 is now a constant that still shapes every key and query

- **Priority:** MEDIUM · **Type:** IMPROVEMENT (post-MVP) · **Area:** `catalogue` persistence model
- **Evidence:** `db/migration/V20260906_130000__support_bounded_catalogue_synchronization.sql:29`
  (`uq_catalogue_revision_singleton ON catalogue.catalogue_publication ((true))`);
  composite keys `(publication_id, game_id)` and `(publication_id, release_id)` on
  `game_snapshot`, `release_snapshot`, `game_alias`;
  `catalogue/adapter/persistence/CurrentPublicationReader.java` (one extra statement
  on every public read); `publicationVersion` carried through `BrowseReleasesResult`
  and `SearchCatalogueResult` but consumed by no mapper (`ReleaseApiMapper`,
  `GameSearchApiMapper` ignore it; ETags hash the body); ADR-0017 ("remain only as
  singleton revision metadata for compatible public reads, seeds and cache
  validators").
- **Problem:** ADR-0017 kept the table consciously, and the implementation matches
  the ADR, so this is not a contradiction. It is a concept that no longer exists in
  the domain (there is one current state, not a publication) but still costs one
  statement per request, 16 bytes in every snapshot and alias key and index, a
  tautological predicate in every read, a `FOR UPDATE` on the singleton row in every
  Game write, and a mental model ("current publication") that new readers must learn
  and then unlearn. The "cache validators" justification is not exercised: no
  validator uses `catalogue_version`.
- **Impact:** simplicity and query-plan clarity rather than correctness; every future
  catalogue table would inherit the axis by convention.
- **Proposal:** after the MVP closes, one forward migration that drops
  `publication_id` from the snapshot and alias keys (keeping `game_id` /
  `release_id` as primary keys), replaces `catalogue_publication` with a one-row
  `catalogue_revision(last_synchronized_at, source_name)` if the metadata is still
  wanted, removes `CurrentPublicationReader`, and deletes `publicationVersion` from
  the application results. Amend ADR-0017's "Current state, not global copies"
  section to record the retirement. `CATALOGUE_NOT_READY` semantics become "no game
  exists" or "no synchronization has ever completed", which the health and the
  frontend already handle.
- **Simpler alternative considered:** keep it; it works and the seeds depend on it.
  This is the correct choice *before* the MVP closes.
- **Trade-offs:** a migration across three tables, the dev seed, every read SQL, the
  scalability ITs, and the fixture SQL in six tests (the code review's `CR-15`
  fixture helper would make this far cheaper, which is an argument for doing that
  first).
- **Cost:** large · **ADR:** yes (amend ADR-0017) · **Issue:** yes, labelled post-MVP

### LOW

#### `AR-09` — The stable code vocabulary in the use-case record and the implemented vocabularies have drifted

- **Priority:** LOW · **Type:** CURRENT_PROBLEM (documentation contradiction) · **Area:** use-case record ↔ identity, synchronization
- **Evidence:** `docs/architecture/application/mvp-use-cases.md` code table
  (`RETURN_CONTEXT_INVALID/EXPIRED/REPLAYED`, `AUTHENTICATION_FAILED/CANCELLED`,
  `PROVIDER_MAPPING_FAILED`, `EXTERNAL_REFERENCE_CONFLICT`, `RELEASE_DATA_INVALID`);
  implementation: `RatingBoundary.Outcome` markers `resumed/expired/cancelled/invalid`,
  `ProviderMappingFailure.RELEASE_DATE_INVALID`, `SynchronizationOutcome` plus string
  outcome codes, no `EXTERNAL_REFERENCE_CONFLICT` (a uniqueness collision surfaces as
  `SynchronizationWriteException` counted as a failed Game).
- **Problem:** the use-case record is the canonical owner of "stable errors"; the
  identity and synchronization slices settled on different names or on markers
  instead of codes. Neither side is wrong in behaviour; the vocabulary is.
- **Proposal:** owner decision: either align the implementation names (cheap for the
  provider enum, awkward for the identity markers, which are query-string values in a
  redirect) or update the record to describe the identity outcomes as redirect
  markers and the provider reasons as `ProviderMappingFailure` values. Recommend the
  latter; it is documentation.
- **Cost:** small · **ADR:** no · **Issue:** no (absorb into the next documentation pass)

#### `AR-10` — OAuth tokens are retained in the servlet session although the BFF never uses them

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** `identity` token boundary
- **Evidence:** `IdentitySecurityConfiguration.java:93-94`
  (`HttpSessionOAuth2AuthorizedClientRepository`); no `OAuth2AuthorizedClient`,
  `RestClient`, or resource-server call anywhere in `src/main`; ADR-0003 ("tokens
  remain server-side").
- **Problem:** the BFF is an OIDC relying party only; it calls no downstream API with
  the access token. Keeping the access and refresh tokens in every session for
  thirty minutes is retained secret material with no consumer, and it is also what
  makes the session the largest replicated object if sessions are ever shared.
- **Proposal:** a no-op `OAuth2AuthorizedClientRepository` (or one that stores nothing
  after the ID token is validated), documented in ADR-0003 as "the BFF retains no
  provider tokens after authentication; reintroduce retention only when a downstream
  resource call is approved".
- **Cost:** small · **ADR:** no (one sentence in ADR-0003 consequences) · **Issue:** yes, grouped with `AR-02`

#### `AR-11` — Localized taxonomy labels are derived in the frontend by matching English display names

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** taxonomy ownership across the contract
- **Evidence:** `frontend/src/shared/catalogue/region-label.ts` (maps `"north america"`,
  `"europe"`, … to Spanish); `db/migration/V20260906_130000…sql` seeds English
  `display_name`; OpenAPI `Platform`/`Region` expose `name` only.
- **Problem:** the product is Spanish-only and the catalogue owns taxonomies, yet the
  Spanish label of a region lives in the frontend keyed by the English display name.
  A renamed or added region silently falls back to English. Nobody owns "the label
  the user sees".
- **Proposal:** decide the owner. Either store the product-facing (Spanish) display
  name in the taxonomy tables, or expose the taxonomy `code` in the contract and let
  the frontend map by code. Both are small; the first needs no contract change.
- **Cost:** small · **ADR:** no · **Issue:** no (fold into the next taxonomy change)

#### `AR-12` — Product rules are restated outside their owning module where the closed modules cannot share them

- **Priority:** LOW · **Type:** noted, not recommended for change · **Area:** `identity`, frontend
- **Evidence:** `identity/adapter/web/RatingBoundary.java` (`MIN_VALUE = 1`,
  `MAX_VALUE = 10`, mirrors `RatingValue`); frontend `MAX_QUERY_CODE_POINTS = 100`
  and the my-ratings `> 100` check (mirror `CatalogueSearchPolicy`).
- **Assessment:** the frontend copies are contract-derived (OpenAPI declares the
  bounds) and correct. The identity copy exists because `identity` may not depend on
  `ratings`; a shared kernel for one integer range is not worth a module. Recorded so
  that a change to `RAT-001` is known to touch three places; no action.
- **Cost:** none · **ADR:** no · **Issue:** no

---

## 5. Scalability and evolution assessment

Following the scalability skill: scale characterized qualitatively (no production
metrics exist; fixtures are not evidence), hot paths traced, bounds checked,
speculative infrastructure rejected.

### Hot paths and their bounds

| Path | Statements per request | Rows into Java | Growth axis in PostgreSQL | Ordering | Cache |
|---|---|---|---|---|---|
| `UC-001` releases | 5 (publication, platforms, regions, count, page) | `pageSize` + taxonomy | count/page over the window (partial GiST) | period, title, `gameId`, `releaseId` | public, `max-age=60`, ETag |
| `UC-002` search | 3 (publication, count, page) | `pageSize × releaseContextLimit` | matching candidates (GIN) | rank, title, `gameId`, then bounded context | public, `max-age=60`, ETag |
| `UC-003` details | 5 (publication, game, aliases, releases, aggregate) | ≤ 100 aliases + ≤ 256 releases + 10 buckets | aggregate over that game's ratings (`ix_rating_game_value`) | period, `releaseId` | public, `max-age=0, must-revalidate`, ETag; `no-store` when aggregate unavailable |
| `UC-005/6` rating write | ~12 across four transactions (find, details read, projection refresh, conditional write, aggregate) | bounded | that game's ratings | n/a | `no-store` |
| `UC-008` my ratings | 2 (count, page) in one repeatable-read transaction | `pageSize` | the user's rated set | key, `gameId` | `no-store` |
| `UC-009` sync per Game | ~20 statements + 2 provider requests, rate-limited at ≤ 3 req/s | `maxReleasesPerGame` | interval size | keyset by provider Game ID | n/a |

All request memory follows the page or the aggregate bound. No path loads a
collection to filter in Java. Every paginated query ends in a unique key. Public reads
carry validators; personal reads are never cacheable. Correctness depends on no
process-local state (the rate limiter is politeness, fenced by the database). The
skill's prohibited patterns are absent.

### 1. Problems that already exist

None of scale. The two current-state findings that touch this area are
`AR-05` (write amplification proportional to catalogue size, not yet painful) and
`AR-06` (a policy interaction, not a load problem).

### 2. Limits foreseeable from the current design

These are consequences of documented decisions; each already has, or gets here, a
trigger. None is current work.

- **Synchronous synchronization POST** (`RT-1`). One HTTP request holds the whole
  interval: at ≤ 3 provider requests per second and two requests per Game, 1,000
  Games take roughly eleven minutes on the management port; a month of IGDB
  release dates can be several thousand Games. The run is durable (`synchronization_run`
  records the outcome, `lastRun` reads it) so a dropped connection loses nothing but
  the response; the operator, however, cannot tell a slow run from a dead one except
  through `lastRun`. ADR-0017 names exactly this trigger.
- **In-memory HTTP sessions** (`RT-2`). CSRF token, authorized client, and rating
  return context live in the servlet session; a second application instance would
  need sticky sessions or a session store. The platform design is one host, one
  instance, so this is not a defect; ADR-0003 states the consequence.
- **Aggregate statistics computed per page view** (`RT-3`). The game page runs the
  ten-bucket aggregate over that game's ratings on every uncached view. It is an index
  range scan proportional to that game's rating count; the domain model already
  defers materialization to "a later decision".
- **Exact counts and `OFFSET`** (`RT-4`). Every list returns `totalItems`/`totalPages`
  and pages by offset, so deep pages and large candidate sets cost PostgreSQL work
  proportional to the offset or the match count. ADR-0015/0016/0018 each name keyset
  pagination as the revisit.
- **Startup backfill of the projection** (`RT-5`). Startup time grows with rated
  games missing a projection row, and an incomplete backfill fails startup for the
  whole application, including public reads. ADR-0018 accepts this and names the
  trigger.
- **Single-provider cover resolution** (`RT-6`). `ProviderCoverReferenceResolver` is
  wired to one implementation and `cover_source` is a free string; a second provider
  needs a resolver registry keyed by source. ADR-0017's "second provider approved"
  trigger covers it.

### 3. Possible future concerns to revisit only with measurements

Read replicas, an HTTP caching intermediary in front of public reads, an
asynchronous projection, a separate search store, cursor pagination in the contract,
and any scheduler or queue for synchronization. Each has an ADR that names the
evidence that would reopen it (ADR-0015, ADR-0016, ADR-0017, ADR-0018); none has that
evidence today, and none should be started on the basis of this review.

### Evolution capacity by module

- **Catalogue:** absorbs new taxonomy values (migration row + adapter mapping),
  larger intervals, more aliases, and editorial content without structural change.
  A second provider is the first structural step (`RT-6`), and the publication axis
  (`AR-08`) is the main simplification available.
- **Ratings:** absorbs more users and ratings; the projection (`AR-05`) and the
  aggregate (`RT-3`) are the two places where growth shows first. Extracting Ratings
  would require breaking `AR-04` and re-homing the projection; nothing in the code
  makes that harder than it needs to be.
- **Identity:** absorbs a second user trivially; a second issuer or provider is the
  structural step (`AR-07`), and it is currently silent.
- **API:** contract growth is linear; the delivery layer's own maintainability is a
  code-review matter (`CR-03`).
- **Frontend:** adding a page is additive; the BFF boundary is stable.

---

## 6. Revisit triggers

Recorded so that they are not rediscovered; a trigger is not an issue.

| ID | Revisit … | When … | First move inside the monolith |
|---|---|---|---|
| `RT-1` | Synchronous synchronization command (ADR-0017) | Measured runs exceed the management-port or reverse-proxy timeout, or the operator cannot distinguish slow from dead | Accept the command, run it on a bounded in-process executor, return `202` with the run id; `lastRun` and the database fence already exist. Persistent checkpoints only if restart-from-scratch is measured to be too costly |
| `RT-2` | In-memory HTTP session (ADR-0003) | A second application instance is approved | `spring-session-jdbc` in the existing PostgreSQL (an `identity` schema), not a cache; `AR-10` first, so sessions carry no tokens |
| `RT-3` | Per-view aggregate statistics | A measured game page cost dominated by the aggregate scan | A Ratings-owned `game_statistics` row maintained in the same rating transaction (already the pattern of the projection); no cache |
| `RT-4` | Exact count + `OFFSET` (ADR-0015/0016/0018) | Measured deep-offset or count latency | Keyset cursor as an additive contract change; drop `totalPages` only with a product decision |
| `RT-5` | Startup backfill (ADR-0018) | Measured startup cost, or a desire to keep public reads up while the private list rebuilds | Report the listing `unavailable` until backfill completes instead of failing startup |
| `RT-6` | Single provider (ADR-0017, ADR-0001) | A second provider is approved | Resolver registry keyed by `cover_source`; a second `CatalogueProviderPort` implementation; provider name becomes a closed enum |
| `RT-7` | Local-only logout | Any second user, or a shared device scenario | RP-initiated logout (`OidcClientInitiatedLogoutSuccessHandler`) as a product decision; the OpenAPI description already says the application session only |
| `RT-8` | Post-index recheck on `publication_id` (ADR-0016) | Measured catalogue growth makes the recheck material | Resolved for free by `AR-08` |

---

## 7. Things reviewed but intentionally not recommended

- **Any service extraction, broker, cache, search store, or second database.** No
  finding in this review needs one; each ADR's trigger remains unmet.
- **Spring Modulith `@ApplicationModuleListener` (asynchronous, after-commit) for
  `GameListingChanged`.** The synchronous in-transaction listener is the deliberate
  ADR-0018 choice so that the private list is never incomplete; the async variant is
  the ADR's own revisit.
- **A shared kernel of identifier types (`GameId`, `UserId`) across modules.**
  Identifiers cross module contracts as strings today; a shared module for two value
  objects would add a Modulith module to protect nothing that constraints and UUID
  parsing do not already protect.
- **Moving `ReleaseStatusPolicy`, `GameImportPolicy`, and `ReleaseReconciliationPolicy`
  into `catalogue.domain`.** They operate on port shapes (`ProviderWork`,
  `PublishedRelease`, `PlannedRelease`); moving them would drag port types into the
  domain or force a second set of records. Selective tactical DDD is the documented
  policy and the current placement is consistent with it.
- **Introducing `Game`/`Release` aggregates for synchronization writes.** The
  reconciliation is a per-Game plan-then-write over port records with the invariants
  in PostgreSQL; an aggregate would rehydrate state the database already guards.
- **Composing UC-003 in the application layer instead of in `GameDetailsEndpoint`.**
  The endpoint calls two module contracts and maps; no policy lives there. A
  cross-module application service would need a home module and add nothing.
- **Removing the IGDB cover resolver from the read composition.** ADR-0001 makes
  provider CDN references the approved cover mechanism; resolving the URL at read
  time from the stored reference is the design, and the composition root is allowed
  to know the adapter.
- **Replacing the process-local IGDB rate limiter.** Politeness only; the database
  single-run fence is what protects correctness.
- **Collapsing the three JDBC/transaction templates into one.** The three execution
  policies (catalogue read-only repeatable-read with query timeout, synchronization
  read-committed writes with a long timeout, ratings operations with a short timeout)
  are distinct on purpose.
- **A `users` or `accounts` table now.** The domain model places accounts outside the
  MVP; `AR-07` asks only for the recipe.
- **An `identity` database schema for sessions now.** Only with `RT-2`.
- **API gateway or management.** Deferred by ADR-0003 with clear triggers; none met.
- **Cursor pagination or count removal now.** `RT-4`.
- **Spring Data JPA removal.** A build/baseline matter recorded in the code review
  (`CR-25`), not an architectural change.

---

## 8. Suggested GitHub issues

Only work that justifies tracking after the MVP; related findings are grouped.

| # | Proposed title | Findings | Priority | Cost | ADR |
|---|---|---|---|---|---|
| 1 | Narrow the rating eligibility contract to release evidence | `AR-01` | MEDIUM | small–medium | no |
| 2 | Bring the rating-intent read into the OpenAPI contract and stop retaining provider tokens | `AR-02`, `AR-10` | MEDIUM | small | no (two sentences in ADR-0003) |
| 3 | Add a SQL-level cross-module fitness function and re-home the catalogue readiness probe | `AR-03` | MEDIUM | small | no |
| 4 | Document the accepted Ratings → Catalogue foreign key | `AR-04` | MEDIUM | small (docs) | no |
| 5 | Refresh the listing projection only for rated games | `AR-05` | MEDIUM | small | note in ADR-0018 |
| 6 | Decide freshness semantics for historical releases | `AR-06` | MEDIUM | small once decided | only if the freshness concept changes |
| 7 | Record identity continuity across an issuer change | `AR-07` | MEDIUM | small | amend ADR-0007 |

`AR-08` (retire the publication axis) is deliberately **not** listed as an issue
yet: it is post-MVP, needs an ADR-0017 amendment, and is far cheaper after the code
review's shared test fixtures exist. `AR-09`, `AR-11`, and `AR-12` are documentation
or opportunistic items. No `RT-*` trigger becomes an issue.

Suggested order: 4 and 7 (documentation, before the MVP closes so the decisions are
on record), then 6 (a product decision that changes user-visible behaviour), then 1,
3, 5, and 2 in any order.

---

## 9. Final assessment

The architecture is coherent, deliberately simple, and better documented than most
systems many times its size. It is ready to close the MVP as it stands: no finding is
a blocker, none contradicts an accepted ADR, and none asks for infrastructure. The
recommendations tighten three seams to what the records already promise (`AR-01`,
`AR-02`, `AR-03`), write down two decisions the code already embodies (`AR-04`,
`AR-07`), and correct two consequences of the newest ADRs before they become visible
(`AR-05`, `AR-06`). The one larger simplification (`AR-08`) is a post-MVP choice that
removes a concept rather than adds one.

The parts that do not need change deserve saying plainly: the module set, the named
interfaces, the hexagonal rules and their tests, the read-model-per-use-case approach,
per-Game synchronization atomicity, the BFF and CSRF design, the contract-first flow,
and the single-database consistency boundary. If the owner accepted no finding from
this document, the system would still be a sound modular monolith with every known
limit recorded next to the evidence that would reopen it.

What was verified: every cited line, migration, and document at `d10bc44`; the
absence of any consumer of the retained OAuth tokens and of `catalogue_version`; the
unconditional projection refresh; the per-release freshness computation; the
`UserId` derivation inputs. What was assumed: the private-dev deployment is one
application instance behind one reverse proxy (per the platform design), and normal
operator use of synchronization is periodic recent/upcoming windows rather than
repeated full-history runs (ADR-0017's stated intent).
