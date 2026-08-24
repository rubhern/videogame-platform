# Local OIDC BFF session

- **Status:** Implemented compatibility evidence for issue #40
- **Version:** 1.2
- **Last verified:** 2026-08-23
- **Boundary:** Private local/CI walking skeleton; not a public-production session design
- **Contract:** [OpenAPI 1.2.0](../architecture/api/openapi.yaml)
- **Identity decision:** [ADR-0007](../decisions/0007-use-keycloak-as-the-initial-identity-provider.md)

## Proven flow

The combined application now proves this real path with Keycloak 26.7:

```text
browser
  -> GET /auth/login/keycloak
  -> Keycloak Authorization Code + OIDC + PKCE S256
  -> allowlisted /login/oauth2/code/keycloak callback
  -> server-side code exchange and ID-token validation
  -> opaque HttpOnly application-session cookie
  -> GET /api/v1/session
  -> CSRF-protected POST /api/v1/session logout
```

The architecture catalogue contains two implementation-backed visual traces:

- [OIDC login and BFF session sequence](../architecture/diagrams/mermaid/oidc-bff-session-sequence.mmd);
- [session, CSRF and logout sequence](../architecture/diagrams/mermaid/session-csrf-logout-sequence.mmd).

They show the exact browser-visible and server-only boundaries proven by issue #40.
The earlier authenticate-and-create-rating sequence remains an approved future
product journey and is not evidence that rating continuation was implemented here.

Spring Security generates and verifies `state`, `nonce`, and PKCE material, exchanges
the authorization code from the backend, validates issuer, signature, audience and
token lifetime, and retains OAuth/OIDC material only in the server-side HTTP session.
The fixed successful destination is `/`; a caller-supplied return URL is ignored.
Callback state is session-bound and single use.

The browser receives no access, refresh, or ID token. The session endpoint exposes
exactly one of these no-store representations:

```json
{ "authenticated": false }
```

```json
{ "authenticated": true, "csrfToken": "opaque-session-bound-value" }
```

It does not expose claims, roles, email, the provider subject, an internal user ID,
expiry information, or tokens. No authentication state is written to local storage
or session storage.

## Configuration and local execution

Issue #40 reuses the realm, confidential client, generated credentials and Compose
topology introduced by issue #21. It does not define a second Keycloak realm or
client configuration.

Start and verify those dependencies:

```bash
bash scripts/local-dependencies.sh up
bash scripts/local-dependencies.sh verify
```

Alternatively, start the same dependencies and the packaged application together:

```bash
docker compose --profile full up --build
```

This profile exposes the application at `http://localhost:8080` and keeps the
browser-facing Keycloak issuer at `http://localhost:8180`.

If the project-scoped volume was created before issue #40, the existing realm will
not be overwritten by Keycloak's safe `IGNORE_EXISTING` import. After confirming that
the volume contains only disposable local data, run
`bash scripts/local-dependencies.sh reset --yes` once and then `up`/`verify` so the
allowlisted callback and complete synthetic test profile are imported.

Build the same-origin application, load the ignored generated environment, and run
the JAR with the opt-in OIDC profile:

```bash
bash scripts/package-application.sh
set -a
source backend/.env
set +a
SPRING_PROFILES_ACTIVE=oidc \
APPLICATION_FLYWAY_ENABLED=true \
SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed \
java -jar backend/target/videogame-platform-backend-0.7.2-SNAPSHOT.jar
```

Open `http://localhost:8080/auth/login/keycloak`. The supported callback is
`http://localhost:8080/login/oauth2/code/keycloak`; successful authentication ends
at `http://localhost:8080/`.

The relevant runtime settings are:

| Setting | Purpose | Safe local default / source |
|---|---|---|
| `SPRING_PROFILES_ACTIVE=oidc` | Enables OIDC client registration | Explicit opt-in |
| `OIDC_ISSUER_URI` | Exact issuer required in ID tokens | `http://localhost:8180/realms/videogame-platform` |
| `OIDC_AUTHORIZATION_URI` | Browser-visible authorization endpoint | Public `localhost:8180` realm endpoint |
| `OIDC_TOKEN_URI` | Server-side code exchange endpoint | Public endpoint for host execution; `keycloak:8080` in Compose |
| `OIDC_JWK_SET_URI` | Server-side signing-key endpoint | Public endpoint for host execution; `keycloak:8080` in Compose |
| `OIDC_USER_INFO_URI` | Server-side UserInfo endpoint | Public endpoint for host execution; `keycloak:8080` in Compose |
| `KEYCLOAK_BFF_CLIENT_SECRET` | Confidential-client authentication | Generated ignored `backend/.env`; required and never logged |
| `APPLICATION_SESSION_TIMEOUT` | Inactivity expiry | `30m` |
| `APPLICATION_SESSION_COOKIE_NAME` | Opaque cookie name | `vgp_session` locally |
| `APPLICATION_SESSION_COOKIE_SECURE` | HTTPS-only cookie transport | `false` only for loopback HTTP; must be `true` for HTTPS |

Plain loopback HTTP cannot set a usable `Secure` cookie. The local proof therefore
uses the unprefixed host-only `vgp_session` cookie with `HttpOnly`, `SameSite=Lax`,
and `Path=/`. An HTTPS environment must use `Secure=true` and the approved
`__Host-vgp_session` name. This is a deliberate local compatibility exception, not
a production default.

The split endpoints do not create a second issuer. The browser and ID token continue
to use the exact Keycloak issuer on `localhost:8180`; only container-to-container
backchannel traffic uses Docker DNS. Issuer and signature validation remain strict.

Normal shutdown preserves the dependency database:

```bash
bash scripts/local-dependencies.sh down
```

## Logout and CSRF

Logout is the state-changing `POST /api/v1/session`; there is no logout `GET`.
Spring Security requires the current session-bound `X-CSRF-Token`, then invalidates
the session, clears authentication and expires only the configured application
cookie. Missing or invalid proof returns RFC 9457 JSON with stable code
`CSRF_VALIDATION_FAILED` and keeps the authenticated session intact.

For browser requests, the BFF additionally rejects a mismatched `Origin` and any
present `Sec-Fetch-Site` value other than `same-origin`. Header absence remains
supported for non-browser clients, which must still supply the session-bound CSRF
token. Authentication and session responses use `Cache-Control: no-store`.

## Repeatable evidence

Run the focused backend security tests with:

```bash
./mvnw -pl backend \
  -Dtest=SessionSecurityIntegrationTest,OidcIdTokenValidationTest test
```

Run the real compatibility proof with:

```bash
bash scripts/validate-identity.sh
```

That gate builds the combined JAR, creates a fresh isolated PostgreSQL 18 database,
imports the existing realm into real Keycloak 26.7, starts the OIDC-enabled packaged
application, and drives Chromium through the actual login form without request
interception or protocol mocking. Generated credentials are random and never
printed. Playwright retries and traces are disabled for this credential-bearing
flow. The gate asserts PKCE login, the allowlisted callback, exact session shapes,
opaque cookie attributes, absence of browser-visible tokens/storage, CSRF rejection,
valid logout and cookie removal. CI runs the same command in the
`identity-compatibility` job.

Backend tests separately reject mismatched state and nonce, issuer, audience,
lifetime and signature, and cover invalid origin/fetch metadata. The tracked Postman
session collection covers anonymous state and the stable missing-CSRF problem
without storing credentials.

## Scalability and failure boundary

| Concern | Current bounded behaviour |
|---|---|
| Request work | `GET` and `POST /session` are constant work and constant response size; no catalogue query, count, scan or provider fan-out occurs. |
| Persistence and memory | Each authenticated browser owns one bounded server-side session. Per request is `O(1)`; session memory grows with active sessions, not catalogue size. |
| Concurrency | Session and CSRF material are isolated by opaque cookie; logout invalidates only that session. |
| Caching | Session state is personal and always `no-store`; intermediary caching is forbidden. |
| Horizontal scale | The current process-local session is intentionally a one-instance compatibility proof. Replication requires an explicitly selected shared or sticky session strategy before deployment; the session must never become product truth. |
| Keycloak failure | Public reads continue. New login and token-dependent activity fail closed; existing local sessions remain usable only while valid. |

No Redis, distributed session store, microservice, paid service, or remote
infrastructure is introduced. Selecting a persistent session strategy belongs to the
deployment evidence that actually requires replication.

## Deliberately out of scope

- login/logout controls or authenticated product UI;
- mapping validated `issuer + subject` to a durable product `UserId`;
- ratings authorization and persistence;
- provider logout or single logout across devices;
- refresh-token lifecycle beyond the bounded Spring Security compatibility proof;
- public HTTPS, remote Keycloak, distributed sessions, or remote provisioning;
- private HTTPS, deployed identity configuration, ratings authentication and the
  later remote acceptance gates; issue #34 already reuses this proof and is closed.

## Semantic Versioning

The new compatible backend/session capability advances the backend reactor from
`0.4.0-SNAPSHOT` to `0.5.0-SNAPSHOT`. Adding the compatible logout operation advances
the OpenAPI contract from `1.1.0` to `1.2.0`. The frontend package remains `0.1.0`
because its runtime product UI did not change; only its generated contract types and
browser compatibility evidence changed. Root tooling and the isolated IGDB PoC are
unchanged. Issue #27 later advances only the backend reactor to `0.6.0-SNAPSHOT` for
the compatible production-image delivery boundary. The optional complete local
Compose topology advances it to `0.7.0-SNAPSHOT` because it adds a compatible runtime
capability with distinct browser-facing and container-internal OIDC endpoints. The
session and OpenAPI contracts remain unchanged.
