# Backend Postman assets

Executable Postman examples for the implemented product and Actuator APIs plus a
non-secret local environment. A separate IGDB collection supports provider exploration
and hypothetical future needs; see the [IGDB quick guide](igdb-exploration.md).
The product collections document implemented behaviour; the
reviewed [`openapi.yaml`](../../docs/architecture/api/openapi.yaml) remains the
product contract, and backend integration tests remain authoritative for PostgreSQL
behaviour, W3C propagation, and negative sensitive-data assertions. Whenever a
backend API changes, update its tracked requests and assertions in the same change.

## Files

| Collection | Covers |
|---|---|
| `catalogue-releases` | Release exploration over the current week or an inclusive range, the highlights calendar's per-day game counts of a month from the current one on and navigation to the full day in the explorer, at most one presented release per platform, the exact-day default after today and approximate-date opt-in, date, Hype, community-score and external player score orders, filters, public headers, weak-validator conditional reads, strict query parameters including the retired `view`, range and order validation, pagination, stable validation errors |
| `featured-releases` | Automatic monthly featured releases: the current Madrid month by default and a requested month of the current year (another year's month is rejected), the bounded ranking, one qualifying release per platform inside the month, an explicit platform restriction applied before ranking and its unknown-platform rejection, no exposed popularity value, a featured release of the month that never presents its cover, public headers, weak-validator conditional reads, malformed-month and strict-query rejection |
| `catalogue-search` | Canonical-title and approved-alias matching, diacritic-insensitive prefix matching, ambiguous and zero-result outcomes, deterministic pagination, separate work-level scores with revalidating public headers, conditional reads |
| `game-details` | Public details, release evidence listed platform by platform with each presented record first, empty statistics, the optional separate IGDB player score, conditional requests, missing games, strict query rejection |
| `personal-ratings` | Authenticated read, conditional create/update/delete, strong ETag reuse from the private collection, scoped search and ordering, the optional separate IGDB player score, duplicate/stale-write and CSRF rejection; private personal statistics with arithmetic/bounds/empty assertions and client-owner rejection |
| `platform-preferences` | The public selectable platforms with their validator, then, with a signed-in session, the user's own never-stored preferred platforms: read, whole-set replacement with de-duplication, rejection of an unknown platform, a client-supplied owner and a missing CSRF token without any change, and the clearing empty set; it ends with the account configured and no platform |
| `catalogue-administration` | Operator-only synchronization status with bounded recent runs, durable coverage of a year as consecutive periods, never-stored responses, stable range and query rejections, and the bounded synchronization request with its CSRF and interval rejections; its last synchronization request asks for a one-day run, which is `SYNCHRONIZATION_DISABLED` without provider credentials; run stop and abandonment with their CSRF rejection and stable not-found answers, naming a run history never holds so no real run is touched; needs an operator session |
| `session` | Anonymous session state, anonymous rejection of catalogue administration, CSRF-protected logout rejection and the inert logout-completion route; successful OIDC login and Keycloak logout are covered by the real-browser identity gate instead |
| `actuator` | Discovery, health groups, build info, metrics, the `cataloguesync` operator command asserting the credential-free `SYNCHRONIZATION_DISABLED` outcome, and `sessionrevocation` with a synthetic `UserId` that has no session plus its malformed-identifier rejection |
| `igdb-exploration` | Development-only IGDB queries; uses `igdb.postman_environment.json`, private provider credentials and the [quick guide](igdb-exploration.md), independently of the backend and its local environment |

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
   **VideoGame Platform - Local** environment, and run the catalogue, featured-releases,
   game-details, actuator, and (while signed out) session collections.

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

## Authenticated platform-preferences run

Its first request, **Selectable platforms**, needs no session and stores the first
platform for the rest of the run. Then follow steps 1–3 of the personal-rating procedure
above and run **Platform Preferences** in its defined order: it bootstraps the session and
CSRF token, replaces the account's preferred platforms, proves each rejection changed
nothing and ends by clearing them. The account then counts as having made its first-run
choice, so the welcome is no longer offered to it.

## Authenticated catalogue-administration run

Follow the personal-rating procedure above with an account that holds the BFF client's
`catalogue-operator` role, granted before that sign-in: authorities are fixed when a
session signs in. The [private-dev runbook](../../deploy/private-dev/README.md#catalogue-operator-role)
owns how the role is configured and granted. Run **Catalogue Administration** in its
defined order; its first request stops the run with an actionable message unless the
session reports the `catalogue_administration` capability.
