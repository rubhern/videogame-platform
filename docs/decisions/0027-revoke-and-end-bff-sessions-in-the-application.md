# ADR-0027: Revoke and end BFF sessions in the application

- **Status:** Accepted
- **Date:** 2026-10-15
- **Owner:** Ruben Hernandez
- **Scope:** BFF session revocation, absolute lifetime and logout with Keycloak
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
- Bound every authenticated BFF session by an absolute lifetime of eight hours,
  matching Keycloak's maximum session. It limits exposure; it is not revocation.
- Logging out of Gameómetro also ends that browser's Keycloak session through
  standard OIDC RP-initiated logout, using the session's ID token and the realm's
  exact post-logout URI; other sessions and users are unaffected.
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

## Revisit when

- Sessions move to a shared store or more than one replica serves requests: the same
  operation must then act on that store, or a durable revocation check returns.
- Sessions become self-contained, leaving no store to invalidate.
- Revocation must originate somewhere the owner procedure cannot reach every session
  store, such as self-service deletion.
