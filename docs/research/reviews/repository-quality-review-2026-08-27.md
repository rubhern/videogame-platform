# Repository quality review — 2026-08-27

- **Type:** Point-in-time engineering review (evidence, not an approved decision)
- **Reviewer:** AI-assisted review pass; the owner retains approval authority
- **Revision reviewed:** `9a23801` on `main`, clean working tree
- **Baseline used:** approved MVP scope in `../../product/product-brief.md` and
  `../../product/mvp-story-map.md`, the structural rules in
  `../../architecture/mvp-solution-architecture.md`, ADR-0001 through ADR-0015,
  `../../architecture/api/openapi.yaml`, and `../../../AGENTS.md`
- **Explicitly not the baseline:** the original vision PDF, deferred roadmap slices,
  and any capability the approved records place outside the current phase

Scope covered: backend Java/Spring code, module and hexagonal boundaries, security and
BFF/OIDC, persistence and migrations, OpenAPI contract-first flow, frontend, tests, CI/CD,
Docker and local topology, observability, scripts, configuration, and documentation.

---

## 1. Executive summary

The repository is in materially better shape than its stage would normally predict. The
approved product records, the architecture records, and the implemented vertical slice
agree with each other to an unusual degree, the boundaries are real rather than aspirational,
and the delivery and security automation is closer to production practice than to a hobby
project. No `CRITICAL` finding was identified: there is no data-loss path, no authentication
or authorization bypass, no secret exposure, and nothing that blocks the approved MVP.

The problems that do exist cluster in three places. First, **release mechanics**: the
artefact version is hardcoded in six places and validated in two, so the next version bump
predictably breaks two required `main` gates. Second, **HTTP edge behaviour that the current
slice does not exercise**: static-asset caching is silently disabled by a Spring Security
default, and the global exception and authentication-entry-point configuration will produce
the wrong status codes as soon as the authenticated half of the MVP arrives. Third,
**duplication that has begun to drift**: the artefact version, the Problem Details body, the
release-browse SQL, and the taxonomy validation rule each exist in more than one place, with
only partial automated protection against divergence.

Findings: **0 CRITICAL, 2 HIGH, 16 MEDIUM, 12 LOW** (30 total).

---

## 2. General project assessment

### 2.1 Overall quality reached at this phase

For a solo, pre-1.0, private learning MVP with one implemented vertical slice
(`GET /api/v1/releases`) plus a real Keycloak-backed BFF session, the engineering quality is
**high**. Judged as a real professionally built product, the backend and delivery pipeline
would pass a serious review with a short defect list; the frontend is thinner but honest
about being a walking skeleton.

The strongest single signal is that the invariants written in `../../../AGENTS.md` and the solution
architecture are actually enforced by executable checks — ArchUnit, Spring Modulith,
Testcontainers-backed constraint and privilege tests, response validation against the
reviewed OpenAPI source, and an aggregate CI gate that fails when an applicable job is
skipped. Most repositories at this stage document such rules and enforce none of them.

### 2.2 Principal strengths

- **Real hexagonal and module boundaries.** `catalogue.domain` has no Spring, `jakarta`,
  `java.sql`, adapter, or generated-type dependency; `ReleaseCatalogueService` is a plain
  class wired from `catalogue.configuration`; generated OpenAPI types are confined to
  `api.delivery` and `api.generated`. `HexagonalArchitectureTest` and `ModularityTest` make
  regressions fail the build rather than pass review.
- **Persistence discipline.** The schema carries genuine invariants (closed-value checks,
  the `date_precision`/value coherence check, `UNIQUE NULLS NOT DISTINCT` on the release
  tuple, a partial unique index for the single current publication, generated
  `period_start`/`period_end` with a bounds check, evidence-backed partial GiST indexes).
  `CataloguePersistenceIntegrationTest` verifies constraints, join cardinality, runtime DML
  privileges without DDL, and Flyway checksum drift. This is the most mature area of the
  repository.
- **Bounded reads by construction.** Filtering, counting, ordering, `LIMIT`, and `OFFSET`
  happen in PostgreSQL, the ordering ends in a unique `release_id`, the read runs in a
  read-only `REPEATABLE READ` transaction with an explicit statement timeout, and
  `usesReleaseIdAsTheUniqueFinalTieBreakerAcrossPages` proves the tie-breaker across page
  boundaries. ADR-0015 records the decision, the alternatives, and the reopening triggers.
- **BFF and session security.** Confidential client, PKCE with `S256`, nonce, disabled
  request cache, fixed post-login redirect, opaque `HttpOnly` session cookie, session-bound
  CSRF material exposed only to authenticated sessions, plus an origin and
  `Sec-Fetch-Site` check layered on top of the CSRF filter. `SessionSecurityIntegrationTest`
  and the real-Keycloak Playwright spec cover the negative cases, including state mismatch
  and cross-origin logout.
- **Delivery and supply chain.** Every action is pinned by SHA, workflow permissions default
  to `{}`, change detection drives selective PR gates, an aggregate gate fails on unexpected
  skips *and* unexpected runs, and publication copies the exact scanned OCI archive with a
  digest equality check rather than rebuilding. Gitleaks, CodeQL, dependency review, npm
  audit, Trivy, and CycloneDX are all wired in.
- **Documentation governance that works.** `../../README.md` names a single canonical owner
  per area, and the documents largely respect it. The persistence Mermaid model matches the
  Flyway SQL column for column. `api-conventions.md` even explains *why* `GET /releases`
  omits `429`. This is rare and worth preserving.

### 2.3 Principal weaknesses and risks

- **Release mechanics are the weakest link** (`DEL-01`). The version and jar name are
  duplicated across `../../../pom.xml`, `../../../Dockerfile`, `../../../compose.yaml`, two validation scripts, a
  Postman assertion, and `../../../backend/README.md`, while `validate-docs.sh` checks only two of
  them. This is the one finding with a predictable near-term failure date.
- **Edge HTTP behaviour is under-specified where the slice does not reach it.** Static
  assets inherit `no-store` from Spring Security (`PERF-01`); there is no
  `AuthenticationEntryPoint` for `/api/**` (`SEC-01`); a catch-all `@ExceptionHandler(Exception.class)`
  will convert future authorization denials into `500` (`COR-01`).
- **Selective duplication has started to drift.** The Problem Details body is hand-built in
  `identity` (`ARCH-02`), the taxonomy rule exists in both the adapter and the application
  service (`ARCH-01`), and the scalability evidence re-declares the production SQL by hand
  in a test that never runs in CI (`TEST-01`).
- **Developer first-run experience contradicts the documented paths.** The two quickstarts
  in the root `../../../README.md` both produce an empty, not-ready catalogue (`DOC-01`), and the
  documented split dev workflow cannot complete an OIDC login at all (`DEL-02`).
- **Frontend maturity lags the backend.** This is appropriate for the phase, but one real
  defect is already present: the release list keys rows by `gameId` when the contract emits
  one row per release (`FE-01`).

### 2.4 Coherence between product, architecture, and implementation

**Strong.** The MVP boundary in the Product Brief, the journey in the story map, `UC-001` in
the use-case record, the `ReleasePage` contract in OpenAPI, ADR-0015, the Flyway schema, and
`JdbcReleaseBrowseReadAdapter` describe the same thing. Specific checks that hold:

- "TBA upcoming sorts last" (`UC-001`) → `NULLS LAST` on `period_start` in `UPCOMING_ORDER`.
- "ordering ends in unique `releaseId`" → `RECENT_ORDER`/`UPCOMING_ORDER` and a dedicated
  cross-page test.
- "no request-path provider call" → the packaged browser test asserts zero IGDB requests.
- "date precision preserved" → `date_precision` column, `ReleaseDate` sealed hierarchy,
  `oneOf` release-date schema, and the Spanish formatter in `release-date.ts`.
- "covers reference the CDN, never copied" → `IgdbCoverReferenceResolver` plus the
  `ck_game_snapshot_cover_delivery_reference` check constraint.

The material incoherences are narrow and listed below: the `429` policy contradicts itself
between `api-conventions.md` and `openapi.yaml` (`API-01`), every OpenAPI identifier example
uses a shape the implementation never emits (`API-02`), `../../../.claude/rules` overstates what the
architecture test enforces (`ARCH-03`), and the platform design overstates the provenance
evidence CI actually produces (`DEL-03`).

**Unimplemented MVP capabilities — search, game detail, ratings, `Mis puntuaciones`,
provider synchronization, remote `dev` — are not treated as defects in this report.** They
are approved scope that is not yet due. Where the current code will *obstruct* those slices,
the finding says so explicitly.

---

## 3. Findings

Ordered by criticality, then by impact within each level.

### HIGH

#### `DEL-01` — Artefact version duplicated across the build, and the next bump breaks required gates
- **Criticality:** HIGH
- **Type:** `DELIVERY`, `MAINTAINABILITY`
- **Location:** `pom.xml:16`, `backend/pom.xml:10`, `Dockerfile:53`, `compose.yaml:72,77`,
  `scripts/validate-browser.sh:85`, `scripts/validate-identity.sh:146`,
  `scripts/package-application.sh:15`, `backend/README.md:110`,
  `backend/postman/actuator.postman_collection.json:254`, `scripts/validate-docs.sh:256-274`
- **Problem:** `0.7.6-SNAPSHOT` and `videogame-platform-backend-0.7.6-SNAPSHOT.jar` are
  written literally in nine places. `validate-docs.sh` validates only that the backend POM
  parent matches the reactor version and that `../../../backend/README.md` names the current jar. The
  two browser and identity gates mount the jar by exact filename
  (`--volume ".../videogame-platform-backend-0.7.6-SNAPSHOT.jar:/application.jar:ro"`).
- **Impact:** A version bump merged to `main` triggers full validation. Docker creates an
  empty directory at the missing volume source, the container fails with
  `Error: Unable to access jarfile`, and both `browser-smoke` and `identity-compatibility`
  fail with a diagnostic that points nowhere near the real cause. The delivery lifecycle
  requires SemVer assessment on every releasable change, so this fires on the first release
  that follows the documented process.
- **Recommendation:** Derive the jar path in the scripts instead of pinning it — e.g. resolve
  it once with `./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout`, or glob
  `backend/target/videogame-platform-backend-*.jar` and fail on anything other than exactly
  one match. Pass `APPLICATION_VERSION` from the POM in `../../../compose.yaml` rather than defaulting
  to a literal. Extend the `validate-docs.sh` version check to cover `../../../Dockerfile`,
  `../../../compose.yaml`, the scripts, and the Postman assertion, so drift fails the documentation
  gate rather than a container gate.

#### `PERF-01` — Hashed SPA assets are served `no-store`, defeating browser caching entirely
- **Criticality:** HIGH
- **Type:** `PERFORMANCE`, `FRONTEND`
- **Location:** `backend/src/main/java/com/videogameplatform/identity/configuration/IdentitySecurityConfiguration.java:44-67`
  (no `headers().cacheControl()` override), `../../../backend/src/main/resources/application.yaml`
  (no `spring.web.resources.cache.*`), built assets
  `frontend/dist/assets/index-<hash>.js` / `index-<hash>.css`
- **Problem:** Spring Security's default header writers include `CacheControlHeadersWriter`,
  which sets `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` (plus
  `Pragma: no-cache`, `Expires: 0`) on any response that has not already set `Cache-Control`.
  `ReleaseController` and the `no-store` API responses set it explicitly and are unaffected,
  but Spring's `ResourceHttpRequestHandler` sets nothing, so every content-hashed Vite bundle
  and the fallback cover SVG are served as uncacheable.
- **Impact:** Every navigation and every hard reload re-downloads the entire JavaScript and
  CSS bundle over the network, even though the filenames are content-addressed and could be
  cached for a year. This directly contradicts the mobile-first release-cut acceptance
  criterion in the story map, and it is invisible to the current gates because no test
  asserts asset cache headers.
- **Recommendation:** Set `spring.web.resources.cache.cachecontrol.max-age: 365d` and
  `cachecontrol.cache-public: true` (plus `immutable` where supported) so hashed assets are
  cached, and keep `/index.html` and every `/api/v1/**` response on `no-store` — the current
  explicit controller headers already guarantee the latter. Add a single assertion to
  `packaged-releases.spec.ts` that the `/assets/index-*.js` response is publicly cacheable
  and that `/api/v1/releases` and `/` are not.

### MEDIUM

#### `SEC-01` — No `AuthenticationEntryPoint` for API paths; every request is `permitAll`
- **Criticality:** MEDIUM
- **Type:** `SECURITY`, `API`
- **Location:** `IdentitySecurityConfiguration.java:44` (`anyRequest().permitAll()`),
  `:50-52` (only an `accessDeniedHandler` is configured),
  `../../architecture/api/api-conventions.md` ("API fetches return `401` rather than redirecting")
- **Problem:** The chain authorizes everything and configures no `authenticationEntryPoint`.
  With `oauth2Login` enabled, the effective entry point is
  `LoginUrlAuthenticationEntryPoint`, which issues a 302 to the authorization endpoint.
  Nothing today is protected, so there is no current defect — but the first
  `.requestMatchers("/api/v1/me/**").authenticated()` will answer an unauthenticated
  `fetch()` with an opaque redirect chain to Keycloak instead of the contracted `401`
  `AUTHENTICATION_REQUIRED` Problem Details that OpenAPI declares for `/me/ratings`.
- **Impact:** The authenticated half of the MVP (`UC-004`–`UC-008`, four of the nine
  contracted operations) will silently violate its own approved API convention, and the
  browser will follow a cross-origin redirect from an XHR context.
- **Recommendation:** Register an `AuthenticationEntryPoint` scoped to `/api/v1/**` that
  emits the `AUTHENTICATION_REQUIRED` Problem Details body with `Cache-Control: no-store` and
  `X-Correlation-ID`, keeping the redirecting entry point for browser navigation under
  `/auth/**`. Add it now, with a test, rather than as part of the first ratings slice.

#### `COR-01` — Catch-all `@ExceptionHandler(Exception.class)` absorbs typed failures
- **Criticality:** MEDIUM
- **Type:** `CORRECTNESS`, `API`
- **Location:** `api/delivery/ApiExceptionHandler.java:227-231`,
  `catalogue/application/CatalogueDataInvalidException.java`,
  `catalogue/adapter/persistence/JdbcReleaseBrowseReadAdapter.java:141-143`
- **Problem:** `CatalogueDataInvalidException` is a deliberately modelled, distinct
  application failure ("persisted local catalogue data cannot be interpreted safely"), raised
  by the row mapper, the cover resolver, and the adapter's generic `DataAccessException`
  branch. It has no `@ExceptionHandler`, so it falls through to the catch-all and becomes a
  generic `INTERNAL_ERROR` `500`. No test pins that mapping — `ReleaseApiFailureIntegrationTest`
  covers `CatalogueReadException` and a raw `IllegalStateException`, but not this one. The
  same catch-all will intercept `AccessDeniedException` / `AuthorizationDeniedException`
  raised inside a controller once method security is introduced, turning a `403` into a `500`
  (the filter-level `CsrfProblemAccessDeniedHandler` only sees exceptions that escape MVC).
- **Impact:** A modelled failure loses its identity at the boundary, contradicting "stable
  codes drive clients" in `mvp-use-cases.md`; a lost `SELECT` privilege on the runtime role
  produces `500 INTERNAL_ERROR` rather than the contracted `503 CATALOGUE_READ_FAILED`; and
  a future authorization rule will report the wrong class of error.
- **Recommendation:** Give `CatalogueDataInvalidException` an explicit handler with an
  intentional status and code, and add the corresponding failure test. Add an explicit
  `@ExceptionHandler(AccessDeniedException.class)` ahead of the catch-all so it can never be
  reclassified as a technical failure. Keep the catch-all as the last resort only.

#### `ARCH-01` — Taxonomy validation lives in two layers, and the adapter copy is load-bearing
- **Criticality:** MEDIUM
- **Type:** `ARCHITECTURE`, `SECURITY`
- **Location:** `JdbcReleaseBrowseReadAdapter.java:184-187,206-209` (`supports()`),
  `catalogue/application/internal/ReleaseCatalogueService.java:98-111` (`validateTaxonomy`),
  `JdbcReleaseBrowseReadAdapter.java:113-114` (`CAST(:platformId AS uuid)`)
- **Problem:** The same rule — "the requested platform/region must exist in the product
  taxonomy" — is implemented twice. The application service owns the stable outcome
  (`PLATFORM_NOT_SUPPORTED`), while the adapter independently short-circuits to an empty
  result. The adapter copy is not redundant: it is the only thing preventing an untrusted
  query string from reaching `CAST(:platformId AS uuid)`. All values are bound parameters, so
  there is no injection risk, but a non-UUID `platformId` that reached the cast would raise a
  `PSQLException` → `DataAccessException` → `CatalogueDataInvalidException` → `500` instead of
  the contracted `422`.
- **Impact:** Correct HTTP behaviour for malformed identifiers depends on an undocumented
  side effect of an adapter optimisation. Any refactoring that removes the short-circuit, or
  a future query path that filters before checking membership, silently converts a validation
  outcome into a technical error. `PlatformId` in OpenAPI is an opaque `string` with no format
  constraint, so nothing at the boundary catches it either.
- **Recommendation:** Make the invariant explicit rather than incidental. Either validate the
  identifier format at the application boundary before building the criteria, or bind the
  parameter as text and compare with `rs.platform_id::text = :platformId` so a malformed value
  simply matches nothing. Then keep the membership rule in exactly one layer and comment the
  adapter short-circuit as a performance optimisation, not a validation.

#### `FE-01` — Release list keys rows by `gameId`, which is not unique in the contract
- **Criticality:** MEDIUM
- **Type:** `FRONTEND`, `CORRECTNESS`, `TESTING`
- **Location:** `../../../frontend/src/features/releases/releases-shell.tsx` (`key={item.gameId}`),
  `../../../frontend/src/features/releases/releases-view-model.ts` (`ReleaseListItem` has no
  `releaseId`), `../../architecture/api/openapi.yaml` (`ReleaseItem` = one game plus **one**
  `release`)
- **Problem:** `GET /releases` returns one item per `release_snapshot` row. A game released on
  four platforms inside the window produces four items sharing the same `gameId`. The view
  model discards `releaseId` entirely, so the React list key is not unique and the row cannot
  even be identified. Additionally every duplicate row renders an identical `Ver {title}` link
  pointing at the same `/games/{slug}`.
- **Impact:** Latent today only because the eight-row dev seed has exactly one release per
  game. With real normalized catalogue data — the normal case — React emits duplicate-key
  warnings and can reconcile sibling rows incorrectly, and the discovery list shows the same
  title several times with several identically named links, which is both a product-quality
  and an accessibility problem (duplicate accessible names for distinct controls).
- **Recommendation:** Carry `release.releaseId` into `ReleaseListItem` and key on it. Then
  raise the product question explicitly with the owner rather than deciding in code: the
  approved records specify one item per release, so either the card should surface the
  platform/region distinction clearly, or the list should group by game. Add a component test
  with two releases of one game.

#### `API-01` — OpenAPI declares `429` on operations the API conventions say cannot produce it
- **Criticality:** MEDIUM
- **Type:** `API`, `DOCUMENTATION`
- **Location:** `docs/architecture/api/openapi.yaml:420,441,232,393,497,533,613,652`,
  `../../architecture/api/api-conventions.md` ("`GET /releases` does not declare `429`: the
  private MVP currently has no application or edge rate limiter responsible for that response")
- **Problem:** The conventions document states the reason `/releases` omits `429` — no
  component owns that response. The identical argument applies to every other operation, yet
  `429 RateLimitExceeded` is declared on `/games`, `/games/{gameId}`, `GET /session`,
  `POST /session`, and all four `/me/ratings` operations. `GET`/`POST /session` are
  implemented today and can never return `429`.
- **Impact:** The contract advertises a response that no code path produces and no gate can
  test, which is exactly the class of drift the contract-first workflow exists to prevent.
  Two approved sources contradict each other; this report does not choose between them.
- **Recommendation:** Resolve the contradiction as one decision: either remove `429` from
  every operation until a limiter with a named owner exists (consistent with the stated
  rationale), or record the deliberate exception in `api-conventions.md`. Whichever is chosen,
  apply it uniformly across the nine operations.

#### `API-02` — Every OpenAPI identifier example uses a shape the implementation never emits
- **Criticality:** MEDIUM
- **Type:** `API`, `DOCUMENTATION`
- **Location:** `../../architecture/api/openapi.yaml` (examples throughout: `platform_ps5`,
  `region_europe`, `game_silksong`, `release_silksong_ps5_eu`, `game_example`) versus the
  actual responses, which emit `platform_id::text` UUIDs such as
  `10000000-0000-4000-8000-000000000001`
- **Problem:** `GameId`, `ReleaseId`, `PlatformId`, and `RegionId` are correctly declared as
  opaque strings, so this is not a schema violation. But every worked example — including the
  ones rendered into the published `../../architecture/api/reference/index.html` — shows a
  readable prefixed identifier the system does not produce. The frontend test fixtures already
  mix both conventions (`gameId: "30000000-..."` alongside `platformId: "windows-pc"`).
- **Impact:** Anyone building against the reference, generating a mock, or writing a fixture
  from the examples encodes the wrong identifier shape. Since the OpenAPI examples are
  themselves gated by `validate-openapi:examples`, the gate confirms they are *valid*, not
  that they are *representative*.
- **Recommendation:** Regenerate the examples from a real response of the current
  implementation. If prefixed identifiers are the intended long-term shape, that is a product
  identity decision that belongs in the domain model and the schema, not only in examples.

#### `ARCH-02` — The Problem Details body is hand-built in the identity module
- **Criticality:** MEDIUM
- **Type:** `ARCHITECTURE`, `API`, `MAINTAINABILITY`
- **Location:** `identity/configuration/CsrfProblemAccessDeniedHandler.java:38-64`
- **Problem:** The CSRF failure response is assembled as a `LinkedHashMap` with literal keys
  (`"type"`, `"code"`, `"category"`, …), duplicating the generated `Problem` model that
  `ApiExceptionHandler` uses for every other error. Two consequences: the RFC 9457 contract
  now has two independent implementations, and an HTTP delivery concern lives in the
  `identity` module, whose declared ownership in the solution architecture is "BFF session and
  external OIDC integration; not product credentials or authorization", while the `api` module
  owns "same-origin HTTP delivery and mapping".
- **Impact:** A change to the Problem contract updates one implementation and silently leaves
  the other behind; only the runtime tests would catch it, and only for the exact fields they
  assert. It also weakens the module ownership boundary that the rest of the codebase respects
  carefully.
- **Recommendation:** Move the Problem serialization into `api.delivery` behind a small
  collaborator (a `ProblemWriter` used by both the `@RestControllerAdvice` and the filter-level
  handler) and have `identity` depend on that contract, or expose the handler from `api` and
  inject it into the security chain. Build the body from the generated `Problem` type in both
  paths.

#### `MAINT-01` — Spring Data JPA is a dependency with zero JPA usage
- **Criticality:** MEDIUM
- **Type:** `MAINTAINABILITY`, `PERFORMANCE`
- **Location:** `backend/pom.xml:49-52` (`spring-boot-starter-data-jpa`),
  `../../../backend/src/main/resources/application.yaml` (`spring.jpa.hibernate.ddl-auto`,
  `spring.jpa.open-in-view`), `catalogue/configuration/CataloguePersistenceConfiguration.java:19-38`
- **Problem:** There is no `@Entity`, no repository interface, and no `EntityManager` anywhere
  in `../../../backend/src`. Persistence is pure `NamedParameterJdbcTemplate`. The starter still pulls
  Hibernate and the JPA autoconfiguration into the runtime image, the two `spring.jpa.*`
  properties are inert documentation, and the injected `PlatformTransactionManager` resolves to
  `JpaTransactionManager` — so the catalogue's read-only JDBC transaction is silently mediated
  by an ORM that manages nothing.
- **Impact:** Larger image, slower startup and higher baseline memory on a 1 GB-limited
  container targeting an OCI Always Free Ampere instance, a larger CVE surface for Dependabot
  and Trivy to churn on, and a non-obvious transaction manager that will confuse the next
  person who debugs isolation behaviour.
- **Recommendation:** Replace `spring-boot-starter-data-jpa` with `spring-boot-starter-jdbc`,
  drop the inert `spring.jpa.*` block, and let `JdbcTransactionManager` back the read
  transaction explicitly. `mvp-technology-baseline.md` already permits "explicit JDBC/SQL read
  models where clearer", so no baseline change is needed; if JPA is intended for the ratings
  aggregate later, reintroduce it in that slice with its first entity.

#### `DEL-02` — OIDC login cannot complete through the documented Vite dev workflow
- **Criticality:** MEDIUM
- **Type:** `DELIVERY`, `FRONTEND`, `DOCUMENTATION`
- **Location:** `../../../frontend/vite.config.ts` (proxies only `/actuator`, `/api`, `/auth`),
  `frontend/README.md:30-31`, `README.md:42`,
  `../../../docker/keycloak/import/videogame-platform-realm.json`
  (`redirectUris: ["http://localhost:8080/...", "http://application:8080/..."]`)
- **Problem:** Spring Security's OAuth2 callback lives at `/login/oauth2/code/{registrationId}`,
  which the dev proxy does not forward. Vite's proxy leaves the `Host` header as
  `localhost:5173`, so `{baseUrl}` resolves to the dev server and the computed `redirect_uri`
  becomes `http://localhost:5173/login/oauth2/code/keycloak` — a URI the realm does not
  allowlist, on a path the proxy does not serve. Both documents state that proxying `/api`,
  `/auth`, and `/actuator` is sufficient for server-owned routes.
- **Impact:** A developer following the documented split workflow gets an opaque Keycloak
  `Invalid parameter: redirect_uri` or a Vite 404 with no explanation. The only working path
  for authenticated development is the packaged application on port 8080, which the docs do
  not say.
- **Recommendation:** Add `/login` to the Vite proxy with `changeOrigin: true` and register the
  `5173` callback in the local realm, **or** state plainly in `../../../frontend/README.md` that the
  authenticated flow requires the packaged application on `:8080` and that the dev server
  supports anonymous browsing only. Either is fine; the current silence is not.

#### `DOC-01` — Both documented first-run paths produce an empty, not-ready catalogue
- **Criticality:** MEDIUM
- **Type:** `DOCUMENTATION`
- **Location:** `README.md:31-51` (both quickstarts), `../../development/local-setup.md`
  (no seed mention), `backend/README.md:55` (the only place the seed is documented),
  `backend/pom.xml:218-232` (`production-image` profile excludes `db/dev-seed/**`),
  `../../../compose.yaml` (`application` service sets no `SPRING_FLYWAY_LOCATIONS`)
- **Problem:** The root README states that the repository "currently proves a PostgreSQL-backed
  release page", then gives two ways to start: `./mvnw spring-boot:run` with migrations
  enabled, and `docker compose --profile full up --build`. Neither loads
  `db/dev-seed/V20260809_130000__seed_bounded_prototype_catalogue.sql`. The Compose path
  cannot: the production image profile deliberately strips the seed from the jar.
- **Impact:** A first run answers `GET /api/v1/releases` with `503 CATALOGUE_NOT_READY` and
  renders "El catálogo local todavía no está disponible." The behaviour is technically correct
  and is the contracted empty-state, but it reads as a broken build and contradicts the
  README's own summary.
- **Recommendation:** Add the `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed`
  line to the README quickstart (or link `../../../backend/README.md` at that point), and state
  explicitly that the Compose `full` profile runs the production image without the seed and
  therefore shows the not-ready state by design.

#### `TEST-01` — Scalability evidence re-declares the production SQL and never runs in CI
- **Criticality:** MEDIUM
- **Type:** `TESTING`, `PERFORMANCE`
- **Location:** `backend/src/test/java/com/videogameplatform/catalogue/adapter/persistence/ReleaseBrowseScalabilityIT.java:190-240`
  (`countSql()`, `pageSql()`, `upcomingWhere()`), `../../../scripts/analyze-release-browse.sh`,
  `../../decisions/0015-query-published-release-pages-with-postgresql.md` ("Evidence and
  reconsideration triggers")
- **Problem:** The test asserts `EXPLAIN` plans against SQL strings written by hand in the test
  file, not against the SQL that `JdbcReleaseBrowseReadAdapter` actually executes. The class
  name ends in `IT`, which matches neither the Surefire default includes nor any configured
  Failsafe execution, so it runs only via the opt-in script.
- **Impact:** The index-usage and no-sequential-scan guarantees that justify ADR-0015 can
  silently stop describing production. A predicate reordering, an added filter, or a change to
  the `MATERIALIZED` CTE would leave the evidence green while the real plan degrades. Because
  the test never runs in CI, the drift would not surface until someone re-ran the script by
  hand.
- **Recommendation:** Have the test obtain its SQL from the adapter rather than restating it —
  for example by exposing the built `count`/`page` statements package-privately, or by
  capturing them through a recording `NamedParameterJdbcOperations`. Keep the run opt-in
  (the 100k-row seed does not belong in every PR), but consider scheduling it on `main` or
  before a named release so the ADR evidence has a refresh cadence.

#### `TEST-02` — The packaged browser gate asserts fixture data inside a moving time window
- **Criticality:** MEDIUM
- **Type:** `TESTING`
- **Location:** `../../../frontend/tests/packaged-releases.spec.ts` (asserts `"Pragmata"` and
  `"2.º trimestre de 2026"` are visible), `backend/src/main/resources/db/dev-seed/…sql`
  (Pragmata = `2026-Q2` → `period_end 2026-06-30`),
  `application.yaml` (`recent-window-months: 6`, evaluated against the system clock)
- **Problem:** Unlike the backend integration tests, which pin a `Clock.fixed` at
  `2026-08-13`, the browser gate runs against the real clock. The `recent` window is
  `today − 6 months … today`. Pragmata leaves that window on 2026-12-31; the other currently
  matching row, Resident Evil Requiem (`2026-02-27`), left it on 2026-08-28 — yesterday
  relative to this review.
- **Impact:** A required `main` gate will start failing on a date rather than on a change,
  with a failure message that points at the UI instead of at the fixture. This is the classic
  shape of a test that erodes trust in the pipeline.
- **Recommendation:** Make the packaged application's clock deterministic for this gate (an
  env-driven fixed instant honoured only outside production, or a seed generated relative to
  "now" at container start), or relax the assertions to structural ones — a non-empty list, a
  well-formed Spanish date string, a working card link — and keep the exact-title assertions
  in the clock-pinned backend integration test where they already are.

#### `TEST-03` — Contract conformance relies on a hand-rolled partial JSON Schema validator
- **Criticality:** MEDIUM
- **Type:** `TESTING`, `MAINTAINABILITY`
- **Location:** `../../../backend/src/test/java/com/videogameplatform/api/delivery/OpenApiResponseContract.java`
  (310 lines), used by `ReleaseApiIntegrationTest` and `ReleaseApiFailureIntegrationTest`
- **Problem:** The class implements its own subset of JSON Schema: `oneOf`, `enum`, `const`,
  `required`, `minItems`, `uniqueItems`, `minLength`/`maxLength`, `pattern`, `minimum`/`maximum`,
  and a few formats. It does not implement `allOf`, `anyOf`, `not`, `$defs`, discriminators,
  `additionalProperties` as a schema, `exclusiveMinimum`/`exclusiveMaximum`, `dependentRequired`,
  or `propertyNames`. `responseSpecification()` is hardwired to `paths./releases.get`, so it
  cannot validate `/session` or any future operation.
- **Impact:** The gate that "proves" runtime responses match the reviewed contract can pass on
  a response that violates a keyword it does not implement, and it cannot be reused as the
  contract surface grows to nine operations. It is also ~310 lines of bespoke validator to
  maintain.
- **Recommendation:** Replace it with a maintained validator — `networknt/json-schema-validator`
  is the smallest mature option and adds no runtime dependency (test scope only) — and
  parameterise the path/method so `/session` and later operations get the same conformance
  check. If the hand-rolled version is kept deliberately, document the supported keyword subset
  in the class Javadoc so its guarantees are not overstated.

#### `ARCH-03` — The agent rule overstates what the architecture test enforces
- **Criticality:** MEDIUM
- **Type:** `ARCHITECTURE`, `TESTING`, `DOCUMENTATION`
- **Location:** `../../../.claude/rules/hexagonal-boundaries.md` ("`HexagonalArchitectureTest` enforces
  them"), `../../../backend/src/test/java/com/videogameplatform/architecture/HexagonalArchitectureTest.java`
- **Problem:** The rule lists nine "Never" constraints and attributes enforcement of all of them
  to the ArchUnit test. Two are not enforced: *"Instantiate an application service or policy
  from an adapter"* and *"Read the host or client default time zone for a time-dependent product
  rule"*. Nothing prevents an adapter from calling `new ReleaseCatalogueService(...)`, and
  nothing prevents `LocalDate.now()`, `Instant.now()`, `ZoneId.systemDefault()`, or
  `Clock.systemDefaultZone()` from appearing in `domain` or `application`.
- **Impact:** The clock rule is the one with real product consequence — the `Europe/Madrid`
  evaluation date is an approved invariant repeated in the solution architecture, the use cases,
  and the API conventions — and it is currently held only by convention and review. The
  documentation claims a stronger guarantee than exists, which is precisely the failure mode
  the repository's own governance rules warn about.
- **Recommendation:** Add two rules: `noClasses().that().resideInAnyPackage("..domain..", "..application..")
  .should().callMethod(LocalDate.class, "now", ...)` and equivalents for `Instant.now`,
  `LocalDateTime.now`, `ZoneId.systemDefault`, `Clock.systemDefaultZone`; and a rule that only
  `..configuration..` may instantiate `..application.internal..` types. Then the rule document's
  claim becomes true.

#### `API-03` — Strict query-parameter enforcement covers only one API interface
- **Criticality:** MEDIUM
- **Type:** `API`, `MAINTAINABILITY`
- **Location:** `api/delivery/StrictQueryParameterInterceptor.java:26-31,72-96`,
  `api/delivery/ApiDeliveryConfiguration.java:19-22` (registered for `/api/v1/**`),
  `../../architecture/api/api-conventions.md` ("Unknown query parameters and command properties
  are rejected, not ignored")
- **Problem:** The interceptor is registered across `/api/v1/**` and its Javadoc calls itself
  "Central enforcement of the API convention that query parameters are closed", but its guard
  returns early unless the handler bean implements `ReleasesApi`. `GET /session` accepts any
  query string today, and each future controller must be remembered explicitly. Two internal
  heuristics are also fragile: a parameter is classified as pagination if it carries `@Min`
  (any future constrained non-pagination parameter would be mislabelled), and enum acceptance
  compares `Object::toString` of the constant against the raw value, which happens to work only
  because the generated `ReleaseView` constants are literally `recent`/`upcoming`. A future
  enum with `PROVIDER_ONLY` ↔ `provider_only` would reject every valid value.
- **Impact:** The convention is enforced for one of nine operations and will quietly stop being
  enforced for the rest; the enum heuristic is a latent correctness bug that will surface the
  first time a wire value differs from its Java constant name.
- **Recommendation:** Drop the `ReleasesApi` restriction and apply the interceptor to any
  handler in `com.videogameplatform.api.delivery`. Classify pagination by parameter name against
  an explicit set rather than by annotation presence, and read enum wire values from the
  generated `getValue()`/`@JsonValue` accessor instead of `toString()`. Add a `/session` case to
  `StrictQueryParameterInterceptorTest`.

#### `PERF-02` — Per-request statement count and in-Java taxonomy ordering
- **Criticality:** MEDIUM
- **Type:** `PERFORMANCE`, `ARCHITECTURE`
- **Location:** `JdbcReleaseBrowseReadAdapter.java:39-48` (`PLATFORM_SQL`, `REGION_SQL` — no
  `ORDER BY`, no `LIMIT`), `:146-204` (five statements per request),
  `ReleaseCatalogueService.java:113-130` (`availableFilters` sorts in Java),
  `api/delivery/ReleaseController.java:49-57` (ETag computed before the `304` check),
  `../../../AGENTS.md` ("Filtering, search, ordering, aggregation, counting, and pagination happen in
  PostgreSQL, not in application code")
- **Problem:** Three related observations. (a) Every `/releases` request executes five
  statements — current publication, all platforms, all regions, count, page — even when the
  taxonomies have not changed. (b) The taxonomy queries have neither `ORDER BY` nor `LIMIT`;
  "bounded taxonomy" is an assumption about the data, not a constraint expressed anywhere, and
  the ordering is then applied with a Java `Comparator`, which is a narrow but literal exception
  to the stated invariant. (c) A conditional request still runs the full query, serializes the
  whole `ReleasePage`, and hashes it before deciding to return `304`, so the validator saves
  bandwidth but no server work.
- **Impact:** None of these threatens the MVP — ADR-0015 measured 1.4–16.3 ms at 100k rows, and
  (c) is an explicit ADR-0015 choice ("Hash the actual JSON for the ETag"). But (b) is a stated
  invariant that the code does not honour, and (a) triples the round trips for data that is
  effectively static.
- **Recommendation:** Move the taxonomy ordering into SQL (`ORDER BY lower(display_name),
  platform_id`) and add a defensive `LIMIT` with an explicit failure if it is reached, so
  "bounded" becomes enforced rather than assumed. Consider folding the publication lookup and
  the two taxonomy reads into one statement. Leave (c) as documented in ADR-0015; if the ETag
  ever needs to short-circuit the query, `catalogue_version` plus `evaluatedOn` is the natural
  weak validator, and that is a contract decision, not a refactor.

### LOW

#### `OBS-01` — Readiness failures leave no diagnostic trail
- **Criticality:** LOW · **Type:** `OBSERVABILITY`
- **Location:** `platform/observability/CatalogueStoreHealthIndicator.java:37-43`
- **Problem:** The `catch (DataAccessException)` branch returns `Health.down().build()` and
  discards the exception. Health details are correctly hidden from the response
  (`show-details: never`), but nothing is logged either.
- **Impact:** A container that fails its readiness probe and is restarted leaves no record of
  *why* — no SQL state, no message, nothing correlatable. This is the exact scenario the
  deployment sequence in the platform design depends on diagnosing.
- **Recommendation:** Log at `WARN` with a stable `error.code` key and the cause, keeping the
  HTTP response body unchanged.

#### `DOC-02` — Observability policy forbids logging what the error handler deliberately logs
- **Criticality:** LOW · **Type:** `DOCUMENTATION`, `OBSERVABILITY`
- **Location:** `../../development/observability.md` ("Never log or export … or arbitrary
  exception text") versus `api/delivery/ApiExceptionHandler.java:288-293`
  (`.setCause(exception)`), asserted as required behaviour by
  `ReleaseApiFailureIntegrationTest` (`loggedThrowable(event)).isSameAs(failure)`)
- **Problem:** The implementation is right — preserving the cause server-side is essential and
  the test enforces it — but the prose forbids it in absolute terms. The following sentence
  ("Error responses expose stable codes … never stack traces or SQL") shows the intent was
  about *responses*.
- **Impact:** An approved document contradicts approved, tested behaviour, and a future reader
  or agent could "fix" the code to match the prose.
- **Recommendation:** Narrow the sentence to responses and exports, and state explicitly that
  the full cause is logged once, server-side, alongside the correlation identifier.

#### `DEL-03` — The platform design overstates the provenance evidence CI produces
- **Criticality:** LOW · **Type:** `DELIVERY`, `DOCUMENTATION`
- **Location:** `../../architecture/deployment/mvp-platform-and-delivery.md` ("produces
  SBOM/provenance evidence"), `scripts/validate-container-image.sh:344-348,405`
  (`--format cyclonedx`, `--provenance=false`),
  `../../../.github/workflows/build-and-verify.yml` (evidence artifacts, 7-day retention)
- **Problem:** Build provenance attestation is explicitly disabled — a defensible choice, since
  it keeps the OCI index to exactly two platform manifests for the digest and platform checks
  in the publish job. The CycloneDX SBOMs are produced but retained as 7-day CI artifacts and
  are not attached to the published image.
- **Impact:** The published image, which is retained indefinitely by commit SHA, outlives its
  SBOM by a wide margin, and the document promises provenance the pipeline does not create.
- **Recommendation:** Either correct the sentence to "SBOM evidence" and record why provenance
  is disabled, or attach the SBOM to the image (`cosign attach sbom` or buildx SBOM
  attestation) and adjust the digest/platform assertions accordingly. Raise the SBOM artifact
  retention if the document's claim is to remain meaningful.

#### `API-04` — Unmatched `/api/v1/**` paths return a bodyless 404
- **Criticality:** LOW · **Type:** `API`
- **Location:** `api/delivery/ApiExceptionHandler.java:222-225`
  (`NoResourceFoundException` → `ResponseEntity.notFound().build()`)
- **Problem:** Every other error on the API surface returns RFC 9457
  `application/problem+json`; an unknown path returns an empty body. This is the right choice
  for static-asset misses such as `/favicon.ico`, but it applies uniformly, including to
  `/api/v1/games`, which OpenAPI declares but the current slice does not implement.
- **Impact:** Minor and mostly cosmetic today, but it is an unstated exception to a documented
  cross-operation rule, and it will read as "endpoint broken" rather than "endpoint not
  deployed" once more operations exist.
- **Recommendation:** Return Problem Details for unmatched paths under `/api/v1/**` and keep the
  bodyless 404 elsewhere; or state the exception in `api-conventions.md`.

#### `FE-02` — No route error boundary and no per-route document title
- **Criticality:** LOW · **Type:** `FRONTEND`
- **Location:** `../../../frontend/src/app/router.tsx` (no `errorElement`),
  `../../../frontend/src/app/app-shell.tsx` (focuses `<main>` on navigation but does not update
  `document.title`), `../../../frontend/index.html` (single static `<title>`)
- **Problem:** A render-time exception falls through to React Router's built-in error element,
  which renders "Unexpected Application Error!" in English — in a Spanish-first product whose
  release-cut acceptance explicitly covers degraded and error states. Route changes move focus
  correctly but the accessible page name never changes.
- **Impact:** A rare failure would surface untranslated and unstyled; screen-reader users get no
  page-identity signal on navigation.
- **Recommendation:** Add a Spanish `errorElement` on the root route reusing the existing error
  card, and set a per-route `document.title` in each page (or via a small route handle).

#### `MAINT-02` — Produced-but-unused state and package placeholders
- **Criticality:** LOW · **Type:** `MAINTAINABILITY`
- **Location:** `catalogue/application/BrowseReleasesResult.java` (`publicationVersion` is the
  first component and is never read — `ReleaseApiMapper.toResponse` ignores it),
  `api/model/ApiModelPlaceholder.java`, `identity/adapter/IdentityAdapterPlaceholder.java`,
  `ratings/{domain,application,adapter}/…Placeholder.java`,
  `catalogue/adapter/provider/igdb/model/IgdbProviderModelPlaceholder.java`
- **Problem:** `publicationVersion` is threaded from SQL through the port and the application
  result and then discarded. Six placeholder classes exist only to reserve package names for
  the architecture tests.
- **Impact:** Low, but both are the kind of scaffolding that becomes permanent. The placeholders
  are a defensible way to make ArchUnit rules meaningful before the code exists; the unused
  result field is simply dead data on a hot path.
- **Recommendation:** Either use `publicationVersion` (it is the natural input for a weak
  validator or a diagnostic header) or remove it until it has a consumer. Leave the placeholders
  but add a one-line comment stating the slice that will replace each one.

#### `TEST-04` — Configuration tests assert wiring rather than behaviour
- **Criticality:** LOW · **Type:** `TESTING`
- **Location:** `catalogue/configuration/CataloguePersistenceConfigurationTest.java:31-56`
  (`isReadOnly()`, `getIsolationLevel()`, `getTimeout()`, `getQueryTimeout()`, bean identity),
  `catalogue/configuration/CatalogueModuleConfigurationTest.java:37-55`,
  `api/delivery/ReleaseApiIntegrationTest.java:120,175-181` (asserting the absence of
  `catalogue.releases.requests`, `…latency`, `…failures`)
- **Problem:** Several assertions restate the configuration line above them, so they can only
  fail when the implementation is edited, never when it is wrong. Others assert that meters
  which were never introduced do not exist.
- **Impact:** These tests raise the cost of refactoring without raising confidence, and they
  dilute the signal in an otherwise high-value suite. The genuinely valuable assertions in the
  same files — that the shared `JdbcTemplate` does *not* inherit the catalogue query timeout,
  and that no stray `NamedParameterJdbcTemplate`/`TransactionTemplate` beans leak — should be
  kept.
- **Recommendation:** Keep the isolation assertions, drop the mirror assertions and the
  never-existed-meter assertions, and express the cardinality policy once as a positive rule
  (every registered meter's tag keys are drawn from an allowlist) rather than as a growing list
  of forbidden names.

#### `MAINT-03` — A closed spike remains a maintained build and CI surface
- **Criticality:** LOW · **Type:** `MAINTAINABILITY`
- **Location:** `../../../tools/igdb-poc` (~2 800 lines), `../../../.github/dependabot.yml` (a dedicated Maven
  ecosystem entry), `../../../.github/workflows/build-and-verify.yml` (`provider-fixtures` job),
  `../../../.github/workflows/security.yml` (CodeQL builds it), `../README.md`
- **Problem:** The PoC completed on 2026-07-24 with `CONDITIONAL_PASS`; the durable result lives
  in `../igdb-poc-results.md`. The tool itself is retained for "reproducible
  capture/offline-validation procedure" and continues to consume a Dependabot lane, a CI job, a
  CodeQL build, and dependency-graph submission.
- **Impact:** Ongoing maintenance and review cost for code that is explicitly "not a production
  adapter", and its normalization logic will be rewritten rather than reused when `UC-009`
  arrives.
- **Recommendation:** Record an explicit retention condition and end date in
  `../../../tools/igdb-poc/README.md` — for example, "retained until the `UC-009` synchronization
  adapter lands, then removed" — so the decision to keep or delete it is deliberate rather than
  inherited.

#### `DATA-01` — The dev seed is time-anchored and relies on Flyway for idempotency
- **Criticality:** LOW · **Type:** `DATA`, `DOCUMENTATION`
- **Location:** `../../../backend/src/main/resources/db/dev-seed/V20260809_130000__seed_bounded_prototype_catalogue.sql`,
  `../../development/database-migrations.md` ("must be deterministic and idempotent for
  disposable use")
- **Problem:** The eight fixture rows carry fixed dates from 2025-06 to 2027, so the number of
  rows visible in the `recent` and `upcoming` views changes as the real clock advances; today
  two rows match `recent` and two match `upcoming`. The file uses plain `INSERT … VALUES` with
  no `ON CONFLICT`, so its idempotency comes from Flyway's version tracking, not from the SQL,
  as the policy sentence implies.
- **Impact:** Demonstrations and any clock-dependent test drift over time (see `TEST-02`), and
  re-running the seed outside Flyway fails on primary-key conflicts.
- **Recommendation:** Either generate the fixture dates relative to the current date at seed
  time, or accept the drift explicitly and say so in the file header. Add `ON CONFLICT DO
  NOTHING` so the SQL matches the documented idempotency claim.

#### `MAINT-04` — Modulith verification repeats at every runtime start
- **Criticality:** LOW · **Type:** `MAINTAINABILITY`, `PERFORMANCE`
- **Location:** `application.yaml` (`spring.modulith.runtime.verification-enabled: true`),
  `backend/pom.xml:44-48` (`spring-modulith-starter-insight`),
  `architecture/ModularityTest.java` (`modules.verify()` at build time)
- **Problem:** The module structure is already proven by a build-time test that gates every
  backend change. Runtime verification re-scans the application classes on every start,
  including inside the published image on a memory- and CPU-limited target.
- **Impact:** Slower startup and higher peak memory during boot, for a guarantee the build
  already provides on the same artefact.
- **Recommendation:** Keep runtime verification enabled for local and test profiles and disable
  it in the packaged runtime, or drop it entirely and rely on `ModularityTest`.

#### `SEC-02` — Frame options relaxed without a stated need, and no Content-Security-Policy
- **Criticality:** LOW · **Type:** `SECURITY`
- **Location:** `IdentitySecurityConfiguration.java:67` (`frameOptions(frame -> frame.sameOrigin())`),
  absence of `headers().contentSecurityPolicy(...)`
- **Problem:** Spring Security defaults `X-Frame-Options` to `DENY`; the configuration weakens
  it to `SAMEORIGIN`. The application renders no iframes, and no comment or document explains
  the change. Separately, the packaged SPA is served with no CSP, although ADR-0001 defines a
  very narrow legitimate image origin (`images.igdb.com`) and everything else is same-origin.
- **Impact:** Both are defence-in-depth rather than active vulnerabilities. A CSP would turn
  ADR-0001's cover-delivery boundary into a browser-enforced constraint instead of a
  server-side convention, which is a meaningful gain for a policy the project treats as a
  licensing obligation.
- **Recommendation:** Restore `frameOptions` to `DENY` unless a need is recorded, and add a
  conservative CSP: `default-src 'self'; img-src 'self' https://images.igdb.com data:;
  script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors
  'none'; base-uri 'none'; form-action 'self'`. Verify against the Playwright gate before
  adopting.

#### `DEL-04` — Non-reproducible runtime layer and a duplicated readiness database probe
- **Criticality:** LOW · **Type:** `DELIVERY`, `OBSERVABILITY`
- **Location:** `Dockerfile:47` (`RUN apk --no-cache upgrade`),
  `application.yaml` (`readiness.include: readinessState,db,catalogueStore`)
- **Problem:** The `apk upgrade` layer floats to whatever Alpine publishes at build time, so two
  builds of the same commit can differ — a trade-off the Dockerfile documents honestly, since it
  clears HIGH/CRITICAL findings ahead of Temurin base rebuilds. Separately, the readiness group
  includes both Boot's `db` indicator and the custom `catalogueStore` indicator, so each probe
  opens two database round trips every ten seconds.
- **Impact:** Rebuild-by-digest is the mitigation for the first and is already the publish
  model, so the practical impact is small. The duplicate probe is negligible load but adds a
  second failure mode to interpret.
- **Recommendation:** Keep the upgrade layer; note the reproducibility trade-off in the platform
  design so it is a recorded decision rather than only a code comment. Consider dropping `db`
  from the readiness group, since `catalogueStore` already proves connectivity *and* schema
  access with a bounded statement timeout.

---

## 4. Cross-cutting observations

### Positive patterns worth preserving

- **Executable enforcement over prose.** Nearly every rule that matters has a gate: module
  boundaries (ArchUnit + Modulith), schema invariants (PostgreSQL constraints + Testcontainers),
  wire contract (generated types + response conformance + Redocly + a committed reference that
  CI diffs), CI itself (`test-ci-change-detection.sh` tests the change detector). The
  documentation-to-implementation drift found in this review is concentrated precisely in the
  few places where no gate exists.
- **Failure paths treated as first-class.** `ReleaseApiFailureIntegrationTest` asserts not only
  the status and code but that the response body leaks no SQL, host, or exception type, that the
  cause is logged exactly once, that the correlation identifier is echoed, and that validation
  failures do *not* emit a technical error log. This is a level of rigour usually absent even in
  production codebases.
- **Untrusted input is consistently treated as untrusted.** Correlation identifiers are regex
  validated before entering logs and headers; every SQL value is a bound parameter and every
  predicate is a compile-time constant; cover references are validated against a pattern in both
  the database constraint and the resolver; metric labels are restricted to a closed vocabulary
  with an explicit `"invalid"` bucket.
- **Least privilege end to end.** Separate migration and runtime database roles, `REVOKE ALL …
  FROM PUBLIC`, a runtime role with DML but no DDL (asserted by test), non-root container,
  `read_only` filesystem, `cap_drop: ALL`, `no-new-privileges`, loopback-only port bindings, and
  a management port that is never published on the product port.
- **Honest scoping.** The records consistently distinguish evidence from decision from
  hypothesis ("synthetic evidence and accepted risk", "hypothesis until evidence supports it",
  "historical evidence, not current operations"). The project resists the common failure of
  presenting aspiration as status.

### Repeated problems spanning several areas

- **Duplication of a single fact across executable sources** is the dominant defect shape:
  the artefact version (`DEL-01`), the Problem body (`ARCH-02`), the taxonomy rule (`ARCH-01`),
  the release-browse SQL (`TEST-01`). Each was individually reasonable when introduced; together
  they are the main maintainability risk. The repository's own governance already names the
  remedy — "identify the single canonical owner before writing" — but applies it to
  documentation, not yet to executable duplication.
- **Framework defaults are inherited without being asserted.** `PERF-01` (Security's
  `Cache-Control`), `SEC-01` (the OAuth2 entry point), `COR-01` (advice precedence over the
  filter chain), and `MAINT-01` (`JpaTransactionManager` as the ambient transaction manager) all
  share one root cause: behaviour that no test states and no configuration line makes explicit.
  Every one of these is invisible in review and would be caught by a single assertion.
- **The "next slice" is under-prepared where the current slice does not reach.** `SEC-01`,
  `COR-01`, and `API-03` are all latent because the authenticated half of the MVP does not exist
  yet. This is the correct order of work, but each is a trap laid for that slice rather than a
  problem it will naturally solve.
- **Time is deterministic in the backend and non-deterministic everywhere else.** The
  application clock is correctly injected and pinned in integration tests, yet the browser gate,
  the dev seed, and the freshness demonstration all run against the wall clock (`TEST-02`,
  `DATA-01`). The discipline exists; it just stops at the process boundary.

---

## 5. Recommended priorities

### Fix now

1. **`DEL-01`** — derive the jar and version in the scripts and Compose file. This has a known
   failure date: the next SemVer bump.
2. **`PERF-01`** — configure static-resource cache control. One property block, user-visible on
   every page load.
3. **`FE-01`** — key the release list by `releaseId`, and put the grouping-versus-per-release
   presentation question to the owner. Cheap now; a data-triggered defect later.
4. **`SEC-01` + `COR-01`** — add the API `AuthenticationEntryPoint` and an explicit
   `AccessDeniedException` handler ahead of the catch-all, with tests. These belong *before*
   the first authenticated endpoint, not inside it.
5. **`DOC-01` + `DEL-02`** — two small documentation corrections that remove the two ways a new
   contributor's first hour goes wrong.
6. **`API-01`** — resolve the `429` contradiction between `api-conventions.md` and
   `openapi.yaml`. Both are approved sources; only the owner can choose.

### Can wait

- **`ARCH-01`, `ARCH-02`, `MAINT-01`** — real structural improvements with no current defect.
  Natural to fold into the next slice that touches persistence, the error boundary, or the
  ratings aggregate respectively.
- **`TEST-01`, `TEST-02`, `TEST-03`** — test-integrity work. `TEST-02` should land before
  2026-12-31; the other two before the contract surface grows past two operations.
- **`ARCH-03`, `API-03`, `PERF-02`, `API-02`** — worth doing, and each is small, but none blocks
  or misleads a user today.
- **All `LOW` findings** — appropriate to batch into a single hygiene pass, or to pick up
  opportunistically when the surrounding file is already being changed.

### Not worth changing now

- **OpenAPI describing seven unimplemented operations.** This is contract-first working as
  designed, not drift. The contract was reviewed as a whole and the slices implement it
  incrementally.
- **The `*Placeholder` classes and empty module packages.** They make the architecture tests
  meaningful before the code exists. A comment naming the owning slice is enough.
- **Page/offset pagination.** ADR-0015 records the decision, the alternative, and the reopening
  trigger, with measured evidence. Changing it now would be optimisation without a trigger.
- **The `MATERIALIZED` CTE and exact-count strategy.** Measured at 1.4–16.3 ms against 100k
  rows. Revisit only against the ADR's stated triggers.
- **`RUN apk --no-cache upgrade`.** The reproducibility cost is real, the security benefit is
  real, the trade-off is documented, and publication is by digest. Leave it; just record it in
  the platform design (`DEL-04`).
- **Committing the 299 KB generated OpenAPI reference HTML.** CI diffs it on every OpenAPI
  change, so it cannot drift, and there is no hosting alternative within the zero-cost
  constraint.
- **Adding a design system, state library, or component abstraction to the frontend.** The
  frontend README explicitly forbids it without demonstrated reuse, and with three pages there
  is none.

---

## 6. Overall conclusion

This repository does something genuinely uncommon: it has built the *discipline* before
building the *product*. One vertical slice is live, yet the boundaries, the contracts, the
constraints, the gates, and the supply chain around that slice are already at a standard that
most teams reach — if at all — well after their first release. The product records, the
architecture records, and the code agree, and where they disagree the disagreements are small,
specific, and listed above rather than systemic.

The honest counterweight is that the same discipline has produced a broad surface for a small
amount of delivered function: sixteen documents, fifteen ADRs, thirty scripts and workflows,
twenty-one skills, and roughly 3 700 lines of backend test code supporting one `GET` endpoint
and a session endpoint. That is a legitimate choice for a project whose stated purpose is
learning solution architecture, and the records say so plainly. But it does mean the marginal
cost of each future slice is now dominated by keeping that surface coherent — which is exactly
why the duplication pattern identified in section 4 is the risk most worth attending to, and why
`DEL-01` sits at the top of the priority list.

Nothing found here threatens data, identity, or the approved MVP. Two HIGH findings should be
fixed in the next working session; both are small. The sixteen MEDIUM findings are a healthy,
addressable debt list for a project at this stage, and roughly half of them are best resolved as
part of the slice that will otherwise trip over them. Judged as a real product built
professionally — and allowing for the early MVP phase, but not excusing it — this is
**above the bar, with a short and specific list of things to correct before the authenticated
half of the journey is built on top of it**.
