# Backend Postman assets

This directory contains executable Postman examples for the implemented product and
Actuator APIs plus a non-secret local environment.

## Files

- [`actuator.postman_collection.json`](actuator.postman_collection.json): discovery,
  aggregate health, liveness, readiness, build info, metric requests, and the internal
  catalogue-synchronization operator command, with automated tests.
- [`catalogue-releases.postman_collection.json`](catalogue-releases.postman_collection.json):
  recent/upcoming release discovery, product filters, public headers, weak-validator
  conditional reads, `int64` page totals, strict query parameters, pagination, and
  stable validation errors with automated tests.
- [`catalogue-search.postman_collection.json`](catalogue-search.postman_collection.json):
  bounded catalogue search by canonical title and approved alias, diacritic-insensitive
  and partial matching, ambiguous and zero-result outcomes, deterministic pagination,
  public headers, weak-validator conditional reads, and stable validation errors with
  automated tests.
- [`game-details.postman_collection.json`](game-details.postman_collection.json):
  public details, release evidence, empty statistics, conditional requests, missing
  games and strict query rejection. Degraded aggregates and populated distributions
  are covered against PostgreSQL by the API integration tests.
- [`personal-ratings.postman_collection.json`](personal-ratings.postman_collection.json):
  authenticated current-user read, conditional create/update/delete, strong ETag
  reuse, duplicate/stale-write rejection, and CSRF rejection. Its bootstrap request
  verifies the browser-created session and stores the CSRF token automatically.
- [`session.postman_collection.json`](session.postman_collection.json): minimal
  anonymous session state and CSRF-protected logout rejection. The successful OIDC
  flow is intentionally covered by the real-browser identity gate instead of
  scripting credentials in Postman.
- [`local.postman_environment.json`](local.postman_environment.json): local product
  `baseUrl` (`http://localhost:8080`) and loopback-only `managementBaseUrl`
  (`http://localhost:8081`).

## Import and run

1. Start the backend from the repository root. The catalogue collections expect a
   fresh disposable local database initialized with the deterministic seed:

   ```bash
   bash scripts/local-dependencies.sh up
   set -a
   source backend/.env
   set +a
   SPRING_PROFILES_ACTIVE=oidc \
   APPLICATION_FLYWAY_ENABLED=true \
   SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed \
   ./mvnw -pl backend spring-boot:run
   ```

   The seed locations must be present when Flyway first migrates this disposable
   database. Do not reset an existing database unless its project-local data is known
   to be disposable; `bash scripts/local-dependencies.sh reset` deletes that local
   PostgreSQL volume and Keycloak state.

2. In Postman, select **Import** and import the environment and the collections.
3. Select the **VideoGame Platform - Local** environment.
4. Run **VideoGame Platform Backend - Catalogue Releases**, **VideoGame Platform
   Backend - Catalogue Search**, **VideoGame Platform Backend - Game Details** and
   **VideoGame Platform Backend - Actuator**, then run
   **VideoGame Platform Backend - BFF Session** while signed out. The Actuator
   collection expects a credential-free local run, so its synchronization requests
   assert the disabled outcome rather than contacting IGDB.

## Authenticated personal-rating run

The application uses a confidential BFF and an interactive Keycloak authorization-code
flow. `/auth/login/keycloak` is browser navigation, not a JSON credential endpoint;
the state, nonce, PKCE verifier, application session and OAuth tokens remain under
Spring Security and Keycloak control. Never add a `POST /login`, passwords, OAuth
tokens or session cookies to a tracked collection or environment.

1. Install Postman Interceptor and enable cookie synchronization between the browser
   and Postman's cookie jar. In Postman, open **Tools → Cookies → Sync Cookies →
   Interceptor**, add `http://localhost`, and start synchronization. See Postman's
   [cookie-sync instructions](https://learning.postman.com/docs/use/capturing-request-data/syncing-cookies/).
2. In that browser, open
   [`http://localhost:8080/auth/login/keycloak`](http://localhost:8080/auth/login/keycloak).
   Sign in with the local test username and password generated in the ignored root
   `.env`, or register a local account on the imported Keycloak page. The credentials
   stay local and are never copied into Postman.
3. After Keycloak redirects to the application, confirm that Postman's cookie manager
   contains `vgp_session` for `localhost`. If Interceptor is unavailable, copy that
   one opaque cookie from the browser's developer tools into Postman's cookie manager;
   Postman documents [manual cookie management](https://learning.postman.com/docs/use/send-requests/response-data/cookies/).
4. Run **VideoGame Platform Backend - Personal Ratings** as a collection, in its
   defined order. Its first request calls `GET /api/v1/session`, requires
   `authenticated: true`, stores `csrfToken` as a collection variable and clears old
   ETag variables. If cookie synchronization is missing or the session expired, that
   request fails with an actionable message and stops the current runner iteration.
5. The remaining requests create, read, reject a duplicate create, update, reject a
   stale update, prove CSRF rejection and delete the rating. The final delete normally
   leaves the released seed game ready for another complete run. If an earlier run
   stopped after creation, read the current rating and delete it with its current
   `ETag` before expecting the create step to return `201` again.

Sending only `GET /auth/login/keycloak` from the Postman runner cannot complete login:
Keycloak still needs an interactive form and the callback must return through the same
pre-authentication application session. The login URL therefore remains a documented
browser entry point rather than an executable API request. Postman's OAuth helper is
also intentionally unused because it would make Postman a token-holding OAuth client,
which is not the approved BFF boundary.

The release collection verifies the reviewed release-page shape, active and available
filters, correlation/cache/ETag headers, `304` weak-validator handling,
date/freshness states, stable Problem Details codes, and equality between each error
body correlation ID and its response header. The search collection verifies the
game-search-page shape, canonical-title and approved-alias matching, the reported match
context, ambiguous and zero-result outcomes, deterministic pagination, the absence of
any provider identifier, and the same header and Problem Details rules. The operational
collection verifies HTTP `200`, discovery links, `UP`
for health and probes, generated build/source metadata, meter names, and bounded HTTP
route tags. Backend integration tests remain authoritative for PostgreSQL behaviour,
W3C propagation, structured correlation, and negative sensitive-data assertions.

The Actuator collection also covers the internal `cataloguesync` operator command
(`UC-009`): the last-run report plus one synchronization run that, without
configured IGDB credentials, returns `SYNCHRONIZATION_DISABLED` and changes nothing.
The command requires inclusive `from` and `to` dates and has no total Game limit;
provider paging is internal. Both requests target the management base
URL. The backend command-boundary test
proves that the command is absent from the product port. A real run with configured
credentials is exercised in the appropriate local environment, never scripted here; the
command is internal and therefore intentionally absent from the product OpenAPI
contract.

To target another instance, change the environment's product and management base
URLs while keeping the management address on its approved private boundary. Do not
add tokens, passwords, cookies, client secrets, or machine-specific values to these
tracked files. Create a private Postman environment for future authenticated APIs.

The collections document implemented behaviour; the reviewed
[`docs/architecture/api/openapi.yaml`](../../docs/architecture/api/openapi.yaml)
remains authoritative for the product contract. Whenever a backend API changes, its
tracked Postman requests and assertions must be updated in the same change.
