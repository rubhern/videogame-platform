# MVP close-out code review

- **Type:** Point-in-time code-quality review (evidence, not an approved decision)
- **Reviewer:** AI-assisted review pass; the owner retains approval authority
- **Revision reviewed:** `d10bc44` on `main`, clean working tree
- **Scope:** backend Java, frontend React/TypeScript, automated tests, and repository
  scripts that carry logic. Architecture, infrastructure, DevOps, and observability
  are out of scope; anything that belongs there is tagged `OUT_OF_SCOPE` and not
  developed.
- **Baseline respected:** `AGENTS.md`, `docs/architecture/mvp-solution-architecture.md`
  (hexagonal dependency rules and Modulith named interfaces), ADR-0001 to ADR-0019,
  `docs/architecture/api/openapi.yaml` (contract-first), `docs/architecture/application/mvp-use-cases.md`,
  the two `.claude/rules/*.md` constraints, and the project backend/frontend skills.
- **Relation to the previous pass:** the
  [repository quality review of 2026-08-27](../reviews/repository-quality-review-2026-08-27.md)
  covered a much wider surface. Findings from that pass that are still open and are
  code-quality matters are re-listed here with their original identifier in brackets so
  the owner can see they have persisted through three more vertical slices.

Nothing in this document changes code or canonical documentation; it is input for the
owner's decision on which recommendations to accept before closing the MVP.

---

## 1. Executive summary

The implementation is in good shape for a solo learning MVP with five vertical slices
(release browse, catalogue search, game details, personal ratings, personal ratings
listing) plus provider synchronization. The architectural rules are real: domain and
application packages carry no Spring, JPA, HTTP, provider, or generated types; every
read is bounded in SQL; tests exercise real PostgreSQL; the OpenAPI contract is the
source of the delivery interfaces. That skeleton is worth protecting and none of the
findings below asks to change it.

The code-quality problems cluster in three places:

1. **Two cross-boundary correctness defects** that are cheap to fix and should not
   ship with the MVP: the same `Platform`/`Region` schema is emitted with a UUID by
   `GET /releases` and with a taxonomy code by `GET /games/{gameId}` (`CR-01`), and
   the packaged backend does not forward the `/mis-puntuaciones` SPA route, so a
   reload or a shared link to that page returns `404` (`CR-02`).
2. **The later slices were written faster than the first ones.** The ratings and
   listing code re-derives values from strings the catalogue already owns as types,
   validates with magic literals instead of the `Query` invariants the other use
   cases use, spells types with inline fully-qualified names, and in the frontend the
   `my-ratings` page abandons the shell/view-model pattern the other pages share.
3. **Duplication that has crossed the threshold where it hurts.** The Problem
   Details vocabulary is spread over a 590-line handler with the same text repeated
   three times; the JDBC adapters repeat the same exception translation and column
   helpers; the frontend repeats cover mapping, status labels, pagination, and
   bounded-integer parsing across slices; and integration tests repeat the catalogue
   fixture SQL in six files.

Findings: **3 HIGH, 12 MEDIUM, 10 LOW** (25 total). Eight GitHub issues are
suggested; the rest can be absorbed by whichever change next touches the file.

---

## 2. Overall code-quality assessment

**Strengths that the review confirms and that should not be traded away**

- `catalogue.domain` (`ReleaseDate`, `CatalogueSearchText`, `CatalogueSlug`) and the
  application policies (`ReleaseStatusPolicy`, `GameImportPolicy`,
  `ReleaseReconciliationPolicy`, `RatingEligibilityPolicy`) are small, pure, well
  named, and tested with fakes rather than mocks.
- The persistence adapters keep every filter, order, count, and page in PostgreSQL,
  bind every external value, and enforce an explicit bound (`MAX_RELEASES`,
  `MAX_ALIASES`, `releaseContextLimit`, sentinel `LIMIT n + 1`). `GameSearchSql` and
  `PersonalRatingsSql` explain their optimization fences in place.
- The IGDB anti-corruption layer (`IgdbCatalogueProviderAdapter`, `IgdbReleaseMapper`,
  `IgdbApiClient`) is exemplary about not leaking payloads, URLs, or credentials into
  logs, metrics, or exception messages.
- Frontend server state is owned by TanStack Query, retries are deliberate, UI states
  are discriminated unions, and navigable state lives in the URL where the product
  requires it. Component tests query by role and stub the network rather than the
  hooks.
- Test quality is above average: application tests use hand-written fakes, contract
  conformance is checked against the reviewed OpenAPI source, and scalability
  evidence reuses the production SQL constants.

**Weaknesses**

- Consistency between slices degrades from the first slice to the last. Releases and
  search are the reference; details, ratings, and listing each drop one or more of
  the conventions (typed queries, view models, imports, shell pattern).
- Strings stand in for closed types at several module and layer boundaries, so
  drift between two enums becomes a runtime `IllegalArgumentException` that the
  catch-all handler turns into `500`.
- Delivery mapping is spread over three mechanisms (typed exceptions, parameter-name
  dispatch, and the generic Spring exception family) inside one class that is now
  the largest in the backend.

The frontend's visual and accessibility work is good; its remaining problems are
structural repetition and one page written in a different style from the rest.

---

## 3. Findings

Priority meaning: `HIGH` = correctness, important maintainability, or defect risk;
`MEDIUM` = clear improvement with observable benefit; `LOW` = worth doing when the
file is touched. Costs: small ≈ under half a day, medium ≈ one to two days, large ≈ more.

### HIGH

#### `CR-01` — `platformId`/`regionId` are UUIDs on `/releases` and taxonomy codes on `/games/{gameId}`

- **Priority:** HIGH · **Area:** backend · **Cost:** small · **Issue:** yes
- **Location:** `backend/src/main/java/com/videogameplatform/catalogue/adapter/persistence/details/JdbcGameDetailsReadAdapter.java`,
  `backend/src/main/java/com/videogameplatform/catalogue/adapter/persistence/releases/JdbcReleaseBrowseReadAdapter.java`,
  `backend/src/main/java/com/videogameplatform/catalogue/adapter/persistence/search/GameSearchSql.java`
- **Problem observed:** the release page selects `p.platform_id::text AS platform_id`
  (`JdbcReleaseBrowseReadAdapter.java:57`) and validates the `platformId` filter against
  those UUIDs; the search context does the same (`GameSearchSql.java`). The details
  adapter selects `p.code AS platform_code` and maps it into the same
  `ReleaseBrowseReadPort.Taxonomy.id` (`JdbcGameDetailsReadAdapter.java:107`, `:158`).
  Both reach the HTTP layer through the single generated `Platform`/`Region` schema,
  whose description says "opaque, provider-independent product identifier".
- **Impact:** the same contract field has two value spaces depending on the
  operation. The integration tests lock both behaviours in
  (`ReleaseApiIntegrationTest` asserts `10000000-0000-4000-8000-000000000001`,
  `GameDetailsApiIntegrationTest` asserts `playstation-5`), and the frontend
  game-details page writes the details value into `?platformId=` while the releases
  page writes the UUID into the same query-parameter name. No current journey crosses
  the two yet, which is why it has not surfaced; the first link from a game page to a
  filtered release list will.
- **Proposal:** select `p.platform_id::text` / `r.region_id::text` in the details
  release query, update the details integration test and the Postman assertion, and
  regenerate the frontend fixtures that use codes (`frontend/tests/fixtures/releases.ts`
  is a mocked fixture and already inconsistent with the real response). The OpenAPI
  examples (`platform_ps5`) were already flagged as never emitted [`API-02`]; align
  them in the same change so the contract, examples, and both endpoints agree.
- **Evidence:** lines cited above; `docs/architecture/api/openapi.yaml` `PlatformId`
  schema (`maxLength: 100`, no format); `frontend/src/features/game-details/game-details-content.tsx:127-160`.

#### `CR-02` — The packaged backend does not forward `/mis-puntuaciones`

- **Priority:** HIGH · **Area:** backend · **Cost:** small · **Issue:** yes
- **Location:** `backend/src/main/java/com/videogameplatform/api/delivery/frontend/FrontendRouteController.java`,
  `frontend/src/app/router.tsx`
- **Problem observed:** the SPA declares `{ path: "mis-puntuaciones" }`
  (`router.tsx:17`) and the header links to it, but `FrontendRouteController` forwards
  only `/`, `/search`, `/games/{slug}`, and `/games/{gameId}/{slug}` (`:14-30`).
  `FrontendRouteControllerTest` explicitly asserts the allowlist, and `UC-008` added
  the route without extending it.
- **Impact:** in the packaged application (the deployment shape the private-dev host
  runs) a browser reload on the personal ratings page, a bookmark, or a pasted link
  yields the bodiless `404` from `NoResourceFoundException`. In-app navigation works,
  so the browser tests, which start from `/`, do not catch it.
- **Proposal:** add the mapping and extend `FrontendRouteControllerTest`; consider a
  single test that reads `appRoutes` paths is out of reach across the language
  boundary, so at least add a comment in `router.tsx` naming the backend allowlist
  as the place that must change together.
- **Evidence:** `FrontendRouteController.java:14-30`; `router.tsx:17`;
  `frontend/src/app/account-control.tsx` (`NavLink to="/mis-puntuaciones"`).

#### `CR-03` — `ApiExceptionHandler` is a 590-line literal catalogue with three dispatch mechanisms

- **Priority:** HIGH · **Area:** backend · **Cost:** medium · **Issue:** yes
- **Location:** `backend/src/main/java/com/videogameplatform/api/delivery/ApiExceptionHandler.java`,
  `backend/src/main/java/com/videogameplatform/api/delivery/StrictQueryParameterInterceptor.java`
- **Problem observed:**
  - the `Problem` title/detail/category/message for one code is written out in full at
    each call site; `FILTER_INVALID` and `PAGINATION_INVALID` texts appear three times
    each (`requestInvalid`, `requestValueInvalid`, `requestConstraintInvalid`), so a
    wording change is a three-place edit with no test catching a miss;
  - `requestConstraintInvalid` dispatches on generated method parameter names
    (`"q"`, `"ifMatch"`, `"ifNoneMatch"`, `"ratingWrite"`, `"page"`, `:429-438`),
    which couples the handler to OpenAPI Generator naming; a renamed parameter silently
    degrades to `FILTER_INVALID`;
  - `ratingNotEligible` compares a `String` with `"RELEASE_REVIEW_REQUIRED"` and then
    calls `Problem.EligibilityReasonEnum.valueOf(exception.reason())` (`:171`, `:187`),
    so a reason added to `RatingEligibilityPolicy.Reason` but not to the contract enum
    is a `500`, not a compile error (see `CR-05`);
  - visibility is mixed (`requestInvalid` is `public`, the rest package-private) and
    one handler imports its exception with an inline fully-qualified name;
  - `StrictQueryParameterInterceptor` special-cases blank values only for
    `RatingsApi` through an inline `RatingsApi.class.isAssignableFrom(...)` (`:58`),
    so the "closed query" rule has two behaviours depending on the interface.
- **Impact:** this class is where every slice converges; it has grown by roughly a
  third per slice and is now the largest backend file. The duplication and the
  string dispatch are the most likely source of a contract regression when the next
  operation is added.
- **Proposal:** introduce one `ProblemCatalogue` (an `EnumMap<ProblemCode, ProblemText>`
  or a small record per code holding status, category, title, detail, default
  pointer, and violation message) built once and asserted complete by a unit test
  over `ProblemCode.values()`. Handlers then reduce to "choose code + pointer". Replace
  parameter-name dispatch with a single `Map<String, ProblemCode>` documented as the
  coupling point and verified by a reflective test over the generated interfaces.
  Make the blank-value rule uniform in the interceptor or document why ratings
  differ.
- **Evidence:** `ApiExceptionHandler.java` (whole file), lines `:171-187`, `:429-451`;
  `StrictQueryParameterInterceptor.java:55-63`.

### MEDIUM

#### `CR-04` — `CatalogueSynchronizationService` mixes run control, per-game reconciliation, and counting in two long methods

- **Priority:** MEDIUM · **Area:** backend · **Cost:** medium · **Issue:** yes (with `CR-09`)
- **Location:** `backend/src/main/java/com/videogameplatform/catalogue/application/synchronization/internal/CatalogueSynchronizationService.java`
- **Problem observed:** `synchronize` is ~90 lines with a `while (true)` loop, an
  inner `try` per page, an outer `catch (RuntimeException)` that increments
  `counts.failed`, completes the run, and then decides whether to rethrow by type
  (`:126-144`). `reconcile` is ~80 lines and derives identity, slug, cover, release
  writes, and five counters in one pass. Outcome codes are string literals
  (`"SYNCHRONIZATION_COMPLETED"`, `"..._WITH_FAILURES"`, `"..._FAILED"`,
  `"..._DISABLED"`, `"..._ALREADY_RUNNING"`, `:119-123`) chosen with a nested
  ternary, and the store writes `'SYNCHRONIZATION_ABANDONED'` as a sixth literal.
  Duplicate provider references are reported by throwing `IllegalArgumentException`
  and catching it two frames up as "failed game" (`:207`, `:180`).
- **Impact:** the class is correct today (the tests are thorough) but hard to change
  safely; each new counter or outcome touches three places, and the exception-as-
  control-flow path also swallows genuine programming errors from the policies
  because `IllegalArgumentException` is caught generically.
- **Proposal:** a `SynchronizationOutcomeCode` enum in `catalogue.application.synchronization`
  (owned next to `SynchronizationOutcome`); split `reconcile` into "plan" (pure,
  returns a `GameWrite` or a typed rejection) and "apply" (store call + counters);
  make the page loop a private method returning the terminal state; replace the
  `IllegalArgumentException` control flow with a typed rejection value. Keep the
  external `SynchronizeCatalogueUseCase` contract unchanged.
- **Evidence:** lines cited above; `JdbcCatalogueSynchronizationStore.java:65`
  (`outcome_code = 'SYNCHRONIZATION_ABANDONED'`).

#### `CR-05` — Closed vocabularies cross module and layer boundaries as `String`

- **Priority:** MEDIUM · **Area:** backend · **Cost:** small · **Issue:** yes (with `CR-06`)
- **Location:** `ratings/application/GetRatingContextUseCase.java` (`Context.reason`),
  `ratings/application/RatingNotEligibleException.java`,
  `catalogue/application/details/GameDetailsResult.java` (`Summary.kind`),
  `api/delivery/catalogue/details/GameDetailsEndpoint.java`,
  `catalogue/adapter/persistence/details/JdbcGameDetailsReadAdapter.java`
- **Problem observed:** `RatingContextService` returns `reason.name()` as a
  `String`; the endpoint does `RatingEligibility.ReasonEnum.valueOf(context.reason())`
  (`GameDetailsEndpoint.java:74`) and the exception handler repeats the pattern
  (`CR-03`). `GameDetailsResult.Summary.kind` is a `String` and the endpoint branches
  on `"editorial".equals(summary.kind())` (`:87`) while `CatalogueCover` in the same
  module models the equivalent choice as a sealed interface. The details adapter
  parses enums with `valueOf(rs.getString(...).toUpperCase(Locale.ROOT))`
  (`JdbcGameDetailsReadAdapter.java:162-171`) although every domain enum already
  provides `fromValue(String)` for exactly this and the release adapter uses it.
- **Impact:** drift between two closed sets is a runtime failure surfaced as
  `INTERNAL_ERROR`; the reader has to know which string values are legal.
- **Proposal:** expose `RatingEligibilityPolicy.Reason` (or a mirrored
  `ratings.application.EligibilityReason`) through `Context` and the exception; make
  `Summary` a sealed interface (`Editorial`, `Sourced`) like `CatalogueCover`; use
  `fromValue` in the details adapter. The named-interface rules already allow both
  moves.
- **Evidence:** lines cited above; `catalogue/application/cover/CatalogueCover.java`
  as the in-repo precedent.

#### `CR-06` — Ratings re-derive the release period by parsing `CatalogueReleaseDate.value()`

- **Priority:** MEDIUM · **Area:** backend · **Cost:** small · **Issue:** yes (with `CR-05`)
- **Location:** `backend/src/main/java/com/videogameplatform/ratings/application/internal/RatingContextService.java`
- **Problem observed:** `periodEnd` rebuilds the end date from the string
  representation: `YearMonth.parse(value)`, `Integer.parseInt(value.substring(6)) * 3`
  for quarters, `LocalDate.of(parseInt(value), DECEMBER, 31)` for years
  (`:52-62`). The catalogue domain already computes `ReleaseDate.periodEnd()` for each
  variant and persists the same value as `period_end`; the rule in `RatingEligibilityPolicy`
  and in `ReleaseStatusPolicy` ("an exact day counts on the day itself, a period only
  once it has ended") is therefore implemented twice from different inputs.
- **Impact:** the quarter format `"%04d-Q%d"` is now a hidden contract between two
  modules; changing it in `ReleaseDate.Quarter.value()` breaks eligibility silently
  (the parse is on a substring offset, not a pattern). It also makes the ratings test
  fixtures encode catalogue formatting knowledge.
- **Proposal:** add `periodStart`/`periodEnd` (`LocalDate`, nullable for unknown) to
  `CatalogueReleaseDate`, filled by `CatalogueReadMapping.toReleaseDate` from the
  domain value. Ratings then consume dates, not strings. This is an application-
  vocabulary addition, not a contract change.
- **Evidence:** `RatingContextService.java:52-62`; `catalogue/domain/ReleaseDate.java`
  (`periodEnd()` per variant).

#### `CR-07` — The personal ratings listing validates with magic strings instead of the `Query` invariants the other use cases use

- **Priority:** MEDIUM · **Area:** backend · **Cost:** small · **Issue:** yes (with `CR-03`)
- **Location:** `ratings/application/ListPersonalRatingsUseCase.java`,
  `ratings/application/internal/PersonalRatingsService.java`,
  `api/delivery/ratings/PersonalRatingController.java`
- **Problem observed:** `BrowseReleasesUseCase.Query` and `SearchCatalogueUseCase.Query`
  validate in their compact constructors and take enums; `ListPersonalRatingsUseCase.Query`
  is an unvalidated record of raw strings and the service branches on
  `"updatedAt"`, `"canonicalTitle"`, `"ratingValue"`, `"asc"`, `"desc"`
  (`PersonalRatingsService.java:30-39`), re-checks `> 100` code points (`:25`) which is
  `CatalogueSearchPolicy.MAXIMUM_QUERY_CODE_POINTS` restated, and re-checks
  `pageSize > 100` (`:41`). The controller applies its own defaults (`page == null ? 1`)
  although OpenAPI declares them, and passes the strings straight through.
- **Impact:** the delivery/application seam is different in this slice from the
  other three, so a reader cannot rely on one rule about where parsing stops.
- **Proposal:** give `Query` a `Sort`/`Direction` enum pair and compact-constructor
  validation (throwing `PersonalRatingsQueryInvalidException` as today), and map the
  contract strings in `PersonalRatingController` as `ReleaseController.toApplicationView`
  does. Optionally promote `RatingSort`/`SortDirection` to named OpenAPI schemas so
  the generator emits enums (that part would follow the `openapi-change` skill).
- **Evidence:** lines cited above; `catalogue/application/releases/BrowseReleasesUseCase.java`
  as the precedent.

#### `CR-08` — JDBC adapters repeat exception translation, column helpers, and bounds

- **Priority:** MEDIUM · **Area:** backend · **Cost:** small · **Issue:** yes
- **Location:** `catalogue/adapter/persistence/**`, `ratings/adapter/persistence/**`
- **Problem observed:**
  - the four-type `catch (CannotCreateTransactionException | DataAccessResourceFailureException | RecoverableDataAccessException | TransientDataAccessException)` → `CatalogueReadException`, `catch (DataAccessException)` → `CatalogueDataInvalidException` block is copied verbatim in `JdbcReleaseBrowseReadAdapter`, `JdbcGameSearchReadAdapter`, and `JdbcGameDetailsReadAdapter` (the third adds `IllegalArgumentException` to the second branch, the others do not);
  - `instant(ResultSet, String)` exists five times with two different implementations (`getObject(OffsetDateTime.class)` in three places, `getTimestamp().toInstant()` in `JdbcGameDetailsReadAdapter` and the ratings adapters);
  - `JdbcGameListingReadAdapter` hard-codes `LIMIT 101` / `> 100` while `GameDetailsReadPort.MAX_ALIASES` names the same bound;
  - `GameDetailsReadPort.Game` is constructed twice, once with empty placeholder lists and once for real (`JdbcGameDetailsReadAdapter.java:81-89`, `:125-133`);
  - taxonomy validity is still checked in both the adapter (`supports`) and the application (`validateTaxonomy`) [`ARCH-01`].
- **Impact:** low individually, but together they make every new read adapter a
  copy-and-adapt exercise, and the two `instant` implementations differ in how they
  depend on the JVM zone for non-`timestamptz` columns.
- **Proposal:** in `catalogue.adapter.persistence` add a `CatalogueReadExecution`-level
  `read(Supplier<T>)` that wraps the transaction and the translation once (the record
  already exists in `CataloguePersistenceConfiguration`), and a `JdbcColumns.instant`
  next to the existing `ReleaseDateRowMapper`/`CatalogueCoverReferenceRowMapper`.
  Reference `MAX_ALIASES` from the listing adapter. Decide the taxonomy-validation
  owner (the application, given it raises the typed exception) and drop the adapter
  copy.
- **Evidence:** file list above; `CataloguePersistenceConfiguration.java` (`CatalogueReadExecution` record).

#### `CR-09` — `CatalogueProviderPort.fetchWorks(List)` is a batch API that both sides use one element at a time

- **Priority:** MEDIUM · **Area:** backend · **Cost:** small · **Issue:** yes (with `CR-04`)
- **Location:** `catalogue/application/synchronization/port/CatalogueProviderPort.java`,
  `catalogue/adapter/provider/igdb/IgdbCatalogueProviderAdapter.java`,
  `catalogue/application/synchronization/internal/CatalogueSynchronizationService.java`
- **Problem observed:** the service always calls `provider.fetchWorks(List.of(providerId))`
  (`:154`) and then checks `works().size() != 1`; the adapter loops per id anyway and
  carries a comment "The application passes one game at a time so provider failures
  isolate that aggregate". `IgdbQueries.worksById(List)` and
  `releaseDatesForGames(List)` exist to support a batch that is never issued.
- **Impact:** the port promises something neither side wants, and the isolation
  invariant lives in a comment instead of the signature. Two provider requests per
  game is the intended bound, but a reader has to prove it.
- **Proposal:** `Optional<ProviderWork> fetchWork(String providerId)` returning the
  statistics alongside; delete the list variants of the queries. This does not
  change provider behaviour or request counts.
- **Evidence:** lines cited above; `IgdbQueries.java:31-52`.

#### `CR-10` — Problem Details JSON is hand-built twice in `identity`, with a third copy of the correlation-id fallback

- **Priority:** MEDIUM · **Area:** backend · **Cost:** small · **Issue:** yes (with `CR-03`)
- **Location:** `identity/configuration/AuthenticationProblemEntryPoint.java`,
  `identity/configuration/CsrfProblemAccessDeniedHandler.java`,
  `api/delivery/ApiExceptionHandler.java`
- **Problem observed:** both security handlers build a `LinkedHashMap` with the
  eight Problem fields, set the same three headers, and define an identical private
  `correlationId()` (`MDC` lookup with UUID fallback); `ApiExceptionHandler` has a
  third, slightly different version that also writes the response header. This was
  reported as [`ARCH-02`] with one copy; there are now two.
- **Impact:** the contract shape of `Problem` is maintained by hand in a module that
  cannot see the generated type (correctly, per the Modulith rules), so a field added
  to the schema must be remembered here.
- **Proposal:** one package-private `ProblemResponseWriter` in
  `identity.configuration` taking `(status, code, category, title, detail)` and a
  shared `CorrelationIds.current(response)` helper in `platform.observability` that
  the API handler can also call. Keep the handlers as thin adapters over it. The
  identity integration tests already pin the JSON shape and would protect the move.
- **Evidence:** the two handlers (`AuthenticationProblemEntryPoint.java:33-54` and `CsrfProblemAccessDeniedHandler.java:43-64`); `ApiExceptionHandler.java:572-581`.

#### `CR-11` — The frontend repeats cover mapping, status labels, pagination, and bounded parsing per slice

- **Priority:** MEDIUM · **Area:** frontend · **Cost:** medium · **Issue:** yes
- **Location:** `frontend/src/features/releases/*`, `frontend/src/features/search/*`,
  `frontend/src/features/game-details/game-details-content.tsx`,
  `frontend/src/features/ratings/my-rating-card.tsx`
- **Problem observed:**
  - `toCover` (attribution-based discrimination) exists in `releases-view-model.ts`,
    `game-search-view-model.ts`, `game-details-content.tsx` (`coverForPresentation`),
    and `my-rating-card.tsx` (inline), each producing the shape `CatalogueCover` wants;
  - `statusLabels` (six Spanish labels) is declared three times;
  - `releases-pagination.tsx` and `game-search-pagination.tsx` differ only in prop
    names and the `aria-label` (verified by `diff`);
  - `readBoundedInteger`, `DEFAULT_PAGE_SIZE`, `MAX_PAGE_SIZE`, `resultsSummary`, the
    "focus the results heading when the page changes" effect, and the
    catalogue-not-ready / error notice blocks are duplicated between the releases
    and search slices.
- **Impact:** the visual rules are centralized in CSS but the behavioural rules are
  not, so a label or a11y fix has to be applied to two to four files, and a new slice
  copies rather than reuses.
- **Proposal:** `shared/catalogue/release-status.ts` (labels), `shared/catalogue/cover.ts`
  (`toCoverPresentation`), `shared/ui/pagination.tsx` (taking a `pathFor(page)`
  callback), `shared/navigation/bounded-integer.ts`, and a `useFocusOnChange(key)`
  hook. Keep the per-slice view models; they should only lose the copied helpers.
  This respects the frontend skill's "extract when genuinely repeated" rule; the
  repetition is now real.
- **Evidence:** files above; `diff releases-pagination.tsx game-search-pagination.tsx`
  shows six changed lines out of ~50.

#### `CR-12` — Four transport error classes with different shapes and different network-failure behaviour

- **Priority:** MEDIUM · **Area:** frontend · **Cost:** medium · **Issue:** yes (with `CR-11`)
- **Location:** `features/releases/releases-api.ts`, `features/search/game-search-api.ts`,
  `features/game-details/game-details-api.ts`, `features/ratings/my-ratings-api.ts`,
  `features/session/session-api.ts`
- **Problem observed:** `ReleasesApiError` has a non-null `status` and `getReleases`
  does not `.catch` the fetch promise, so a network failure surfaces as a raw
  `TypeError` that `ReleasesPage.toFailureState` types as `ReleasesApiError` and reads
  `.code` from; `GameSearchApiError` has `status: number | null` and catches;
  `GameDetailsApiError` has no status; `MyRatingsError` has no status and no message
  mapping. `getSession` discards `error` entirely: a `500` from `/session` is
  reported as "anonymous", so `MyRatingsPage` renders "Necesitas una sesión activa"
  for a server error.
- **Impact:** the "technical failure" UI state is reachable with different fidelity
  per slice, and the session collapse hides a real failure behind a product state
  the skill says must stay distinct.
- **Proposal:** one `ProductApiError` in `shared/api/` (`status | null`, `code | null`,
  `correlationId | null`, `kind: "http" | "network"`) and an `unwrap(promise)` helper
  that every feature function uses; per-feature `kind` maps (as
  `personal-rating-api.ts` already does well) stay on top. Make `getSession`
  distinguish `authenticated: false` from failure.
- **Evidence:** `releases-api.ts:32-45`; `game-search-api.ts:29-35`; `session-api.ts:11-22`.

#### `CR-13` — `MyRatingsPage` abandons the shell/state-union pattern and carries a remount hack

- **Priority:** MEDIUM · **Area:** frontend · **Cost:** medium · **Issue:** yes (with `CR-11`)
- **Location:** `frontend/src/pages/my-ratings-page.tsx`, `frontend/src/features/ratings/my-rating-card.tsx`
- **Problem observed:** the page holds session gating, query, form state, input
  validation that restates contract literals (`[...q].length > 100`, sort/direction
  string checks), results, empty states, pagination, and a refresh button in one
  121-line component written in a compressed single-line JSX style unlike every other
  file. `revision` state is incremented after a refetch only to change the card
  `key` and reset the cards' local edit state. `my-rating-card.tsx` duplicates the
  `failureMessages` table of `game-rating-panel.tsx` with slightly different copy.
  Keeping personal filters in component state is a documented decision
  (`frontend/README.md`) and is not questioned here.
- **Impact:** the page is the hardest frontend file to read and to test in
  isolation; the remount hack couples parent and child state through a `key`.
- **Proposal:** mirror the releases/search structure: `MyRatingsShell` with a
  discriminated `state`, a `my-ratings-view-model.ts`, a `MyRatingsFilters` form
  component, and a `useMyRatingsQuery` hook. Reset card edit state through an
  explicit prop or by lifting `editing` into the card's own `useEffect`-free reset on
  `item.personalRating.entityTag` change instead of a counter. Share the failure copy
  or keep the two tables side by side with a comment explaining the intended
  difference.
- **Evidence:** `my-ratings-page.tsx` (whole file), `:17`, `:47-52`, `:109`.

#### `CR-14` — `GameDetailsContent` mixes URL-driven selection with presentation and formats timestamps by string slicing

- **Priority:** MEDIUM · **Area:** frontend · **Cost:** small · **Issue:** yes (with `CR-11`)
- **Location:** `frontend/src/features/game-details/game-details-content.tsx`
- **Problem observed:** the component derives `platforms`, `platform`,
  `platformReleases`, `regions`, `region`, and `releases` from the raw generated
  `GameDetails` and `useSearchParams` inline (`:125-141`), then contains
  `selectPlatform` with its own region fallback rule (`:156-168`); the same rule is
  restated in the region radio `onChange`. Three timestamps are rendered with
  `value.slice(0, 10)` (`:94`, `:103`, `:113`), i.e. the UTC calendar day of an ISO
  instant, which can differ from the Madrid day the rest of the product uses. It is
  also the only slice that renders generated types directly in JSX instead of a view
  model, which the frontend skill discourages.
- **Impact:** the selection rule is untestable without rendering, and the date
  slicing is a small correctness gap around midnight.
- **Proposal:** a pure `selectReleaseContext(game, searchParams)` returning
  `{ platforms, platform, regions, region, releases }` with a unit test, a
  `game-details-view-model.ts` for labels and cover, and a `formatInstantDay(iso)` in
  `shared/catalogue/release-date.ts` using `Intl.DateTimeFormat("es-ES", { timeZone: "Europe/Madrid" })`.
- **Evidence:** lines cited above.

#### `CR-15` — Catalogue fixture SQL is hand-written in six integration tests

- **Priority:** MEDIUM · **Area:** tests · **Cost:** medium · **Issue:** yes
- **Location:** `GameDetailsApiIntegrationTest`, `PersonalRatingApiIntegrationTest`,
  `CataloguePersistenceIntegrationTest`, `JdbcReleaseBrowseReadAdapterIntegrationTest`,
  `JdbcGameSearchReadAdapterIntegrationTest`, `JdbcPersonalRatingStoreIntegrationTest`,
  plus the three `*ScalabilityIT` classes
- **Problem observed:** each test inserts `catalogue.game`, `game_snapshot`,
  `game_release`, `release_snapshot` (and sometimes aliases) with its own multi-line
  `INSERT` text and its own column list; `GameDetailsApiIntegrationTest` uses a
  parameterised `CASE WHEN ? = 'day'` insert with nine positional parameters. A
  `FixedClock` `@TestConfiguration` is declared three times with three different
  instants/zones.
- **Impact:** any column added to a snapshot table is a six-file edit, and the tests
  couple to the physical schema rather than to a described fixture ("a released
  verified day release on PS5/Europe").
- **Proposal:** a `CatalogueFixtures` helper in `com.videogameplatform.test` with a
  small builder (`game().title(..).release().day(..).platform(PS5).verified()`) that
  emits the SQL once, and a shared `FixedClockConfiguration` parameterised by property
  (`platform.clock.fixed-instant` already exists in `PlatformConfiguration` and is a
  better lever than a `@Primary` bean). The existing `PostgreSqlTestDatabase` is the
  right home for the helper.
- **Evidence:** `grep -c "INSERT INTO"` per file: 10, 11, 10, 2, 3, 2;
  `FixedClock*` in three API tests.

### LOW

#### `CR-16` — Frontend tests each hand-roll a `fetch` stub with URL routing

- **Priority:** LOW · **Area:** tests · **Cost:** small · **Issue:** yes (with `CR-15`)
- **Location:** `frontend/src/pages/*.test.tsx`, `frontend/src/features/**/*.test.tsx` (seven files use `vi.stubGlobal("fetch", ...)`)
- **Problem observed:** every page test defines `requestUrl(input)`, a `serve()` that
  routes `/api/v1/session`, `/auth/rating-intent`, and the slice endpoint, and a
  `*Requests()` accessor over `vi.mocked(fetch).mock.calls`.
- **Impact:** a new endpoint in the header (for example a second session read) has
  to be added to seven stubs.
- **Proposal:** `src/test/stub-product-api.ts` exporting `stubProductApi({ session, game, releases, search, myRatings })`
  and `requestsTo(pathPrefix)`. No new dependency is needed; MSW is not required for
  this size.
- **Evidence:** `game-details-page.test.tsx:9-40`; `releases-page.test.tsx:70-100`.

#### `CR-17` — Inline fully-qualified names and dead placeholders in the later slices

- **Priority:** LOW · **Area:** backend · **Cost:** small · **Issue:** no (absorb in `CR-03`/`CR-07`/`CR-08`)
- **Location:** `api/delivery/ratings/RatingApiMapper.java`, `api/delivery/ratings/PersonalRatingController.java`,
  `api/delivery/ApiExceptionHandler.java`, `api/delivery/catalogue/details/GameDetailsEndpoint.java`,
  `ratings/configuration/RatingsConfiguration.java`, `catalogue/configuration/CatalogueModuleConfiguration.java`,
  `catalogue/configuration/CataloguePersistenceConfiguration.java`,
  `catalogue/adapter/persistence/synchronization/JdbcCatalogueSynchronizationStore.java`,
  `api/model/ApiModelPlaceholder.java`
- **Problem observed:** `RatingApiMapper.toResponse` spells four generated types
  with full package paths inside one expression; the same style appears in the
  configuration classes for the listing slice and in
  `JdbcCatalogueSynchronizationStore` (`java.util.function.Consumer`,
  `org.springframework.jdbc.core.RowCallbackHandler`). google-java-format does not
  fix this. `ApiModelPlaceholder` still has no purpose [`MAINT-02`].
  `GameSearchController implements CatalogueApi` and serves `getGame` through a
  composed `GameDetailsEndpoint`, so the class name describes half of what it does.
- **Proposal:** imports; delete the placeholder; rename to `CatalogueController`.

#### `CR-18` — Four definitions of a valid `gameId`

- **Priority:** LOW · **Area:** backend · **Cost:** small · **Issue:** no
- **Location:** `identity/adapter/web/RatingBoundary.java:31` (`[a-z0-9-]{1,100}`),
  `api/delivery/frontend/FrontendRouteController.java:30` (`[a-z0-9-]+`),
  `catalogue/adapter/persistence/details/JdbcGameDetailsReadAdapter.java:49` (UUID regex),
  `ratings/adapter/persistence/JdbcPersonalRatingStore.java` (`UUID.fromString` guard)
- **Problem observed:** identity's route allowlist is deliberately about safe
  redirects and can stay lexical, but the two persistence adapters express "a product
  game identifier is a UUID" with different code, and `JdbcGameListingReadAdapter`
  does not guard `UUID.fromString` at all (it is only reached with trusted input
  today).
- **Proposal:** one `ProductIdentifiers.parseGameId(String) -> Optional<UUID>` in
  `catalogue.application` (ratings may depend on `catalogue::application`) used by
  both adapters.

#### `CR-19` — Aggregate statistics failures are swallowed without any trace

- **Priority:** LOW · **Area:** backend · **Cost:** small · **Issue:** no (absorb in `CR-08`)
- **Location:** `ratings/adapter/persistence/JdbcRatingStatisticsReadAdapter.java:20`
- **Problem observed:** `catch (DataAccessException | IllegalArgumentException | ArithmeticException _)`
  returns `Unavailable` with no log. Degrading is the approved behaviour
  (`RATING_STATISTICS_READ_FAILED`); silence is not required by it. The only signal
  is the `aggregate=unavailable` metric tag written by the endpoint.
- **Proposal:** log at `WARN` with the bounded `error.code` key the API handler
  already uses, without the SQL or message. (`OUT_OF_SCOPE`: alerting on that signal.)

#### `CR-20` — `GameDetailsService` hard-codes the product zone the clock already carries

- **Priority:** LOW · **Area:** backend · **Cost:** small · **Issue:** no
- **Location:** `catalogue/application/details/internal/GameDetailsService.java:46`
- **Problem observed:** `LocalDate.ofInstant(now, ZoneId.of("Europe/Madrid"))`
  while `ReleaseCatalogueService` uses `clock.getZone()` and
  `CatalogueSynchronizationService` uses `LocalDate.now(clock)`; `PlatformConfiguration`
  builds the clock with the product zone. Three spellings of one rule.
- **Proposal:** `LocalDate.ofInstant(now, clock.getZone())` and a note in
  `PlatformConfiguration` that the zone is the product rule's single owner.

#### `CR-21` — Version precondition is verified three times on update and delete

- **Priority:** LOW · **Area:** backend · **Cost:** small · **Issue:** no
- **Location:** `ratings/application/internal/PersonalRatingService.java:51-56`,
  `ratings/adapter/persistence/JdbcPersonalRatingStore.java` (`requireCurrentVersion`, `UPDATE ... AND version_token = :expected`)
- **Problem observed:** the service reads and compares (so conflict is reported
  before eligibility, which a test pins), the adapter reads and compares again to
  distinguish `404` from `412`, and the statement carries the predicate. Two reads
  per write are not a scale problem for personal ratings; the intent, however, is
  documented nowhere.
- **Proposal:** keep the SQL predicate as the guarantee, keep the service pre-check
  for ordering, and drop the adapter pre-read by classifying a zero-row result with
  one follow-up `SELECT` only on that path; or leave it and add the one-line comment.

#### `CR-22` — Undocumented dev-only cover overlay inside the releases view model

- **Priority:** LOW · **Area:** frontend · **Cost:** small · **Issue:** no
- **Location:** `frontend/src/features/releases/releases-view-model.ts:72-108`, `frontend/src/shared/ui/catalogue-cover.tsx` (`"local-preview"`)
- **Problem observed:** `readLocalCoverPreviews()` reads `VITE_LOCAL_COVER_PREVIEWS`,
  guarded by `import.meta.env.DEV`, and injects a third cover kind. Only the releases
  slice has it, no README or `.env.example` mentions the variable, and only
  `.gitignore` (`frontend/public/local-preview/`) hints at the feature.
- **Proposal:** either document it in `frontend/README.md` ("Visual development") and
  apply it through `shared/catalogue/cover.ts` for every slice, or remove it now that
  the visual foundation is done.

#### `CR-23` — No formatter in the frontend toolchain

- **Priority:** LOW · **Area:** tooling · **Cost:** small · **Issue:** yes (with `CR-11`)
- **Location:** `frontend/package.json`, `frontend/eslint.config.js`
- **Problem observed:** the backend has Spotless; the frontend has ESLint only.
  `my-ratings-page.tsx`, `my-rating-card.tsx`, and `my-ratings-api.ts` are visibly
  formatted differently from the rest (multiple statements per line, JSX on one
  line), which the reviewer had to read around.
- **Proposal:** Prettier (or ESLint stylistic rules) with a `format:check` step in
  `frontend:verify`. This is a linters-do-it item only once the linter exists.

#### `CR-24` — `validate-docs.sh` embeds ~400 lines of Python in a bash heredoc

- **Priority:** LOW · **Area:** tooling · **Cost:** small · **Issue:** no
- **Location:** `scripts/validate-docs.sh:178-572`
- **Problem observed:** the documentation gate is a Python program (`python3 - "$ROOT_DIR" <<'PY'`)
  inside a shell script, so it gets no syntax highlighting, no linting, and no unit
  test, unlike `scripts/test-private-dev-oidc-provisioning.py`, which is a proper
  module with a test.
- **Proposal:** move the body to `scripts/validate_docs.py`, keep the shell wrapper
  for the required-files list and the entry point. (`OUT_OF_SCOPE`: the hard-coded
  `required_files` list as a CI design.)

#### `CR-25` — `spring-boot-starter-data-jpa` is still on the classpath with zero JPA usage

- **Priority:** LOW · **Area:** backend · **Cost:** small · **Issue:** yes (owner decision)
- **Location:** `backend/pom.xml:51`, `backend/src/main/resources/application.yaml` (`spring.jpa`)
- **Problem observed:** no `jakarta.persistence` or `org.springframework.data` import
  exists in `src/main`; every adapter uses `NamedParameterJdbcTemplate`. The starter
  pulls Hibernate and its auto-configuration into every start-up and test context
  [`MAINT-01`]. The technology baseline *permits* JPA "only in adapters"; it does not
  require it, so replacing the starter with `spring-boot-starter-jdbc` is consistent
  with the approved records, but it is a build change the owner should confirm
  explicitly.
- **Proposal:** swap the starter, delete the `spring.jpa` block, and adjust the one
  sentence in `mvp-technology-baseline.md` to "JDBC read/write adapters; JPA/Hibernate
  admissible in adapters when a case justifies it".

---

## 4. Things reviewed but intentionally not recommended

- **Application-level enum mirrors** (`CatalogueReleaseStatus` vs domain
  `ReleaseStatus`, `BrowseReleasesResult.Source` vs `SourceKind`, `CatalogueFreshness`
  vs `FreshnessStatus`). They look like duplication, but `catalogue.domain` is not a
  Modulith named interface, so `api.delivery` and `ratings` genuinely cannot see the
  domain enums; the mirrors are the price of that decision and the mapping switches
  are exhaustive. Exposing `domain` as a named interface would be an architecture
  change, not a code fix. The only inconsistency worth noting is that some mirrors are
  top-level (`CatalogueReleaseStatus`) and some nested (`BrowseReleasesResult.Source`);
  not worth a move on its own.
- **Splitting `ReleaseReconciliationPolicy.reconcile` (nine parameters)**. The
  parameters are all genuinely distinct inputs and the method is pure and tested;
  a parameter object would add a type without removing a concept.
- **Replacing hand-written fakes with Mockito** in the application tests, or the
  reverse. The current fakes (`FakeStore`, in-memory ports) are the better choice and
  are consistent.
- **Merging `ReleaseApiMapper.toReleaseDate` and `GameSearchApiMapper.toReleaseDate`.**
  They are identical, but they target different generated `*ReleaseDate` families
  because the OpenAPI schemas are distinct; a shared helper would need generics over
  generated constructors. Unify the schemas first (an `openapi-change`) or leave it.
- **Per-game write path in `JdbcCatalogueSynchronizationStore.save`** (one
  `SELECT` + up to two `INSERT`s per release). It is bounded by
  `maxReleasesPerGame` and runs in a background operator command; batching would be
  speculative optimization.
- **`GameRatingPanel`'s resume effect with `eslint-disable-next-line react-hooks/exhaustive-deps`.**
  It is a real "run once when the external precondition becomes true" synchronization
  and is protected by a ref and a 400-line test suite; extracting it to a hook would
  move, not remove, the disable comment.
- **`CatalogueDataInvalidException` having no dedicated handler** (it reaches the
  catch-all and becomes `INTERNAL_ERROR`). Invalid persisted data is a defect, and
  `500` with a logged cause is the honest response.
- **The `IgdbApiClient` token cache using `Instant.now()`** rather than the
  application clock. Token lifetime is a transport concern, not a product rule, and
  the rule in `.claude/rules/hexagonal-boundaries.md` is about product time.
- **`OUT_OF_SCOPE` notes** (recorded, not developed): static-asset caching and CSP
  headers [`PERF-01`, `SEC-02`]; `CorrelationIdFilter` and `ObservabilityConfiguration`;
  the metric-naming choices in `ReleaseApiMetrics`/`GameSearchApiMetrics`; the
  deployment and container validation scripts (`validate-container-image.sh`,
  `test-private-dev-deployment.sh`, `validate-private-dev-runtime.sh`); Modulith
  runtime verification at every start [`MAINT-04`]; the required-files list design in
  `validate-docs.sh`.

---

## 5. Suggested GitHub issues

Only work that justifies a tracked change is listed; related findings are grouped.
Titles are proposals; labels follow `docs/development/work-management.md`.

| # | Proposed title | Findings | Priority | Est. cost |
|---|---|---|---|---|
| 1 | Emit the same `platformId`/`regionId` identifier on every catalogue operation | `CR-01` | HIGH | small |
| 2 | Forward `/mis-puntuaciones` from the packaged backend | `CR-02` | HIGH | small |
| 3 | Centralize the Problem Details catalogue and type the delivery seams | `CR-03`, `CR-07`, `CR-10`, part of `CR-17` | HIGH | medium |
| 4 | Type the cross-module rating and catalogue vocabulary | `CR-05`, `CR-06`, `CR-20` | MEDIUM | small |
| 5 | Simplify the synchronization service and its provider port | `CR-04`, `CR-09` | MEDIUM | medium |
| 6 | Share JDBC read-adapter helpers and remove duplicated bounds | `CR-08`, `CR-19`, `CR-18` | MEDIUM | small |
| 7 | Consolidate shared frontend catalogue presentation and align the ratings pages | `CR-11`, `CR-12`, `CR-13`, `CR-14`, `CR-23` | MEDIUM | medium (split into two PRs: shared helpers, then `my-ratings`/details) |
| 8 | Introduce shared test fixtures for catalogue data and the frontend API stub | `CR-15`, `CR-16` | MEDIUM | medium |

`CR-25` (unused JPA starter) needs an explicit owner decision before it becomes an
issue, because it edits a sentence of the technology baseline. `CR-21`, `CR-22`, and
`CR-24` are recorded for opportunistic clean-up and do not justify their own issue.

Suggested order: 1 and 2 before the MVP close (they are user-visible), 3 and 4 next
because they reduce the risk of every later change, then 7 and 8 as the frontend and
test hygiene pass, with 5 and 6 whenever synchronization or a new read adapter is
touched.

---

## 6. Final assessment

The code is ready to close the MVP once `CR-01` and `CR-02` are fixed; nothing else in
this review blocks the release, and no finding asks the owner to revisit an approved
architecture, contract, or technology decision. The recommendations are conservative
on purpose: they consolidate what the repository already does well in its first two
slices and bring the later three slices up to that standard. Accepting issues 3, 4,
7, and 8 would leave the codebase in a state where the next vertical slice has one
obvious place to put each new error, type, label, and fixture; declining them would
be a reasonable choice for a learning MVP as long as the owner accepts that each new
slice will continue to copy rather than reuse.

What was verified: every cited line was read at revision `d10bc44`; the
`platformId` divergence was confirmed against both integration tests and the
frontend usage; the missing route was confirmed against the controller, its test,
and the router. What was assumed: that the private-dev deployment serves the packaged
SPA through `FrontendRouteController` (per `docs/architecture/deployment/mvp-platform-and-delivery.md`),
and that no client currently correlates platform identifiers across the two
operations. No test suite was executed for this review; the findings are from
reading, not from running.
