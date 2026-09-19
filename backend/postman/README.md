# Backend Postman assets

Executable Postman examples for the implemented product and Actuator APIs plus a
non-secret local environment. The collections document implemented behaviour; the
reviewed [`openapi.yaml`](../../docs/architecture/api/openapi.yaml) remains the
product contract, and backend integration tests remain authoritative for PostgreSQL
behaviour, W3C propagation, and negative sensitive-data assertions. Whenever a
backend API changes, update its tracked requests and assertions in the same change.

## Files

| Collection | Covers |
|---|---|
| `catalogue-releases` | Recent/upcoming discovery, filters, public headers, weak-validator conditional reads, strict query parameters, pagination, stable validation errors |
| `catalogue-search` | Canonical-title and approved-alias matching, diacritic-insensitive prefix matching, ambiguous and zero-result outcomes, deterministic pagination, conditional reads |
| `game-details` | Public details, release evidence, empty statistics, conditional requests, missing games, strict query rejection |
| `personal-ratings` | Authenticated read, conditional create/update/delete, strong ETag reuse from the private collection, scoped search and ordering, duplicate/stale-write and CSRF rejection |
| `session` | Anonymous session state and CSRF-protected logout rejection; the successful OIDC flow is covered by the real-browser identity gate instead |
| `actuator` | Discovery, health groups, build info, metrics, and the `cataloguesync` operator command asserting the credential-free `SYNCHRONIZATION_DISABLED` outcome |

`local.postman_environment.json` holds the product `baseUrl` (`http://localhost:8080`)
and loopback-only `managementBaseUrl` (`http://localhost:8081`). To target another
instance change those values while keeping the management address on its private
boundary. Never add tokens, passwords, cookies, client secrets, or machine-specific
values to these tracked files.

## Import and run

1. Start the backend with the deterministic seed on a fresh disposable database
   (the seed locations must be present when Flyway first migrates it; do not reset an
   existing database unless its data is disposable):

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

2. Import the environment and the collections, select the
   **VideoGame Platform - Local** environment, and run the catalogue, game-details,
   actuator, and (while signed out) session collections.

## Authenticated personal-rating run

The BFF is a confidential OIDC client: `/auth/login/keycloak` is browser navigation,
not a JSON credential endpoint, and Postman's OAuth helper is intentionally unused
because it would make Postman a token-holding client outside the approved boundary.
Never add a `POST /login`, passwords, tokens or session cookies to a tracked file.

1. Enable cookie synchronization between the browser and Postman with Postman
   Interceptor (**Tools → Cookies → Sync Cookies → Interceptor**, domain
   `http://localhost`); see Postman's
   [cookie-sync instructions](https://learning.postman.com/docs/use/capturing-request-data/syncing-cookies/).
2. In that browser open `http://localhost:8080/auth/login/keycloak` and sign in with
   the local test account generated in the ignored root `.env`, or register a local
   account on the Keycloak page.
3. Confirm Postman's cookie manager holds `vgp_session` for `localhost`. Without
   Interceptor, copy that one opaque cookie from the browser's developer tools
   ([manual cookie management](https://learning.postman.com/docs/use/send-requests/response-data/cookies/)).
4. Run **Personal Ratings** as a collection in its defined order. Its first request
   calls `GET /api/v1/session`, requires `authenticated: true`, stores `csrfToken` and
   clears old ETag variables; a missing session fails with an actionable message.
5. The remaining requests create, read, reject a duplicate create, update, reject a
   stale update, prove CSRF rejection and delete the rating, leaving the seed game
   ready for another run. If an earlier run stopped after creation, read and delete
   the rating with its current `ETag` before expecting the create step to return `201`.
