# ADR-0027: Revoke and end BFF sessions in the application

- **Status:** Accepted
- **Date:** 2026-10-15
- **Owner:** Ruben Hernandez
- **Scope:** BFF session revocation, ordinary/remembered lifetimes and logout with Keycloak
- **Relates to:** [ADR-0003](0003-use-a-same-origin-bff-and-http-json-api.md) and [ADR-0007](0007-use-keycloak-as-the-initial-identity-provider.md)

## Context

Public accounts require that disabling or deleting an account stops its new operations
on the next request, including through an already-open BFF session; the
[platform design](../architecture/deployment/mvp-platform-and-delivery.md#public-production-target)
owns that requirement. The BFF keeps its OIDC authentication in an in-memory servlet
session on one replica and never calls Keycloak after sign-in, so Keycloak's own session
idles out while the BFF session stays active, and a BFF logout leaves Keycloak's
session alive. Disabling and deleting accounts are owner-run procedures (#274), so the
moment a revocation is needed is always known. The
[#274 comparison](../research/public-release-rights-and-privacy.md#41-immediate-revocation)
holds the evidence.

## Decision

- Revoke by invalidating, directly in the BFF, every session whose derived `UserId`
  matches, on every device, with Spring Security's standard session registry. The
  owner procedure triggers it through an operation on the private management
  interface, after disabling or deleting the Keycloak user and ending its Keycloak
  sessions, and verifies the reported outcome.
- If invalidation cannot be guaranteed, restart the application: its sessions live
  only in memory, so a restart is the fail-closed emergency procedure.
- **Owner amendment, 2026-10-25 (#374):** use one policy in local, private-dev and
  prepared production. Ordinary authentication keeps Keycloak SSO 30 minutes idle /
  eight hours maximum and BFF 30 minutes idle / eight hours from the original ID
  token `auth_time` (`iat` fallback when absent). Silent re-entry cannot reset it.
- Native opt-in Remember Me uses Keycloak SSO seven days idle / thirty days maximum.
  Each remembered BFF ends at thirty idle minutes or the earlier of eight hours
  from its authenticated-session creation and thirty days from original `auth_time`.
  Neither requests nor silent re-entry reset that original cap. These bounds limit
  exposure; they are not revocation. Application cookies remain session-only;
  application sessions and tokens remain in memory on the server.
- A minimal compiled Keycloak Protocol Mapper SPI emits boolean
  `gameometro_remember_me` from `UserSessionModel.isRememberMe()`, exclusively in
  the BFF client's ID token. It implements no authentication or lifetime logic.
  The BFF reads only the ID token validated by Spring (signature, issuer, audience,
  lifetime and request nonce), never userinfo, a user attribute, query, cookie or
  browser preference. Only boolean `true` selects remembered bounds. Missing,
  malformed or unrecognized values use the ordinary policy. A remembered session
  without original `auth_time` or server creation time is refused. Expired callback
  authentication ends the new BFF and requests credentials (`prompt=login`) rather
  than admitting an immediately expired session and repeating silent authentication.
- Logging out of Gameómetro also ends that browser's Keycloak session through
  standard OIDC RP-initiated logout, using the session's ID token and the realm's
  exact post-logout URI; other sessions and users are unaffected.
- After BFF expiry, `/auth/logout` can navigate an anonymous browser to the same
  fixed provider logout endpoint with `client_id` and the registered post-logout
  URI, without keeping an expired ID token. Keycloak requires its own confirmation
  and session-code proof before ending that browser's SSO. GET never ends a live
  authenticated BFF. Authenticated application logout retains same-origin and CSRF
  protection; a stale POST `403` only falls back after a fresh successful session
  read proves the BFF is anonymous. The owner clarified on 2026-10-26 that logout
  appears only while the application displays an authenticated account. Anonymous
  entry offers sign-in and registration only; surviving Keycloak SSO permits the
  intended remembered re-entry. The anonymous completion route recovers an explicit
  logout click whose BFF expired in flight; it is not an anonymous header action.
- Realm imports initialize new realms; the shared idempotent Admin API command
  reconciles existing realms with snapshots and a provider-presence check. Local
  startup applies only session settings and the BFF mapper, preserving its account
  policy. Private-dev's reviewed full identity alignment includes the same fields;
  existing production can use the session-only command during an authorized rollout.
  These prepared operator paths do not establish deployed acceptance.
- Add no per-request database check and no new dependency.

## Alternatives

- PostgreSQL revocation row checked on every authenticated request: correct and
  durable, rejected while sessions have a store that can be acted on, because it adds
  a query to every request carrying a session for a few events per month.
- Keycloak back-channel logout: rejected as the revocation mechanism because an idle
  Keycloak session has nothing left to notify while the BFF session is active, and
  delivery is not retried. It may later complement sign-outs started in Keycloak.
- Revalidating with Keycloak on every request: rejected; it couples each request to
  identity latency and availability. Periodic refresh only bounds the delay.
- Absolute lifetime alone: rejected; it bounds exposure without revoking.
- Built-in user-session-note/AMR mappers: rejected for mode selection after the
  [#374 source review and real-Keycloak probe](../research/browser-session-lifetime.md#7-374-mode-distinction-design-gate).
  The native flag is a property, not an exported session note. Extending every BFF
  from creation would weaken ordinary authentication; retaining eight hours from
  original authentication for both modes would abandon the approved thirty-day UX.

## Consequences

- The management operation can end any account's sessions, so it stays on the
  private management interface, never on the public port or behind the edge, and
  reveals no user identifier in logs or metric labels.
- A request already authenticated when invalidation happens may still finish; the
  owner procedure deletes ratings only after such requests can no longer complete
  and verifies the result.
- The logout response changes from a plain session end to a browser navigation
  through Keycloak, a contract change that follows the OpenAPI workflow.
- An application restart signs every user out; it is reserved for failures.
- The mapper is packaged once in the shared Keycloak image. Compilation uses only
  that pinned runtime's existing API JARs, targets its Java baseline and bundles no
  dependency. Keycloak labels `protocol-mapper` an internal SPI and warns it may
  change without notice. Its documented extension surface is not a promise of binary
  compatibility: every Keycloak upgrade must rebuild it and pass signed false/true,
  absent-claim, silent-entry, logout and BFF regression tests. Never deploy a realm
  switch alone ahead of the reviewed BFF and provider image. Existing realms need
  a reviewed, snapshotted alignment; startup import does not update accounts.
- Native username hints can preselect Remember Me on later credential forms. The
  shared theme resets the checkbox on every displayed form; persistence always
  requires an explicit opt-in. A browser restoring session cookies can retain an
  ordinary BFF only within its server bounds. A normal browser restart drops the
  BFF cookie; remembered SSO permits a new BFF after sign-in navigation.
- #374 is prepared source, not deployed behavior. Private-dev retains its previous
  live configuration until the owner deploys and aligns it; production remains
  unbuilt and gated. Operator and manual verification steps belong to the local
  setup and private-dev guides, not this ADR.

## Revisit when

- Sessions move to a shared store or more than one replica serves requests: the same
  operation must then act on that store, or a durable revocation check returns.
- Sessions become self-contained, leaving no store to invalidate.
- Revocation must originate somewhere the owner procedure cannot reach every session
  store, such as self-service deletion.
