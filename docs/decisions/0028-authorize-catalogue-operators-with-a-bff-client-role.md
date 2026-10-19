# ADR-0028: Authorize catalogue operators with a BFF client role

- **Status:** Proposed
- **Date:** 2026-10-18
- **Owner:** Ruben Hernandez
- **Scope:** Product authorization for catalogue administration reads (#247), the bounded synchronization request (#248) and the stop or abandonment of a run (#342)
- **Relates to:** [ADR-0003](0003-use-a-same-origin-bff-and-http-json-api.md), [ADR-0007](0007-use-keycloak-as-the-initial-identity-provider.md), [ADR-0017](0017-discover-catalogue-members-automatically-from-igdb.md#coverage-and-freshness) and [ADR-0027](0027-revoke-and-end-bff-sessions-in-the-application.md)

## Context

Until #247 every authenticated session had the same rights: its own ratings. Catalogue
synchronization status and coverage were readable only on the private management port,
which needs SSH or a tunnel. #247 asks the owner to read that evidence in the product, so
the BFF must tell an operator from an ordinary player. Identity is deliberately only the
validated issuer and subject, roles were never read, and the BFF never calls Keycloak
after sign-in. The browser must not receive tokens or raw claims, and management
endpoints must stay off the product port.

## Decision

- Keycloak owns who is an operator through one client role, `catalogue-operator`, of the
  `videogame-platform-bff` client. Nobody holds it by default; the owner grants it to a
  named account.
- A dedicated client-role mapper writes that client's roles into the ID token only, as
  `product_roles`, never into access tokens, user info or introspection. The BFF reads the
  claim from the ID token it already validates (signature, issuer, audience) and maps only
  the reviewed role name to the Spring Security authority `ROLE_CATALOGUE_OPERATOR`;
  other values are ignored. Identity stays issuer and subject.
- Authorization happens server-side on the product port: `/api/v1/admin/**` requires the
  authority. An anonymous request is `401 AUTHENTICATION_REQUIRED`; a signed-in session
  without the authority is `403 ACCESS_DENIED`, before any validation or read.
- `GET /session` may list server-derived capabilities, never roles or claims, so the
  frontend can offer navigation; hiding navigation is never the control.
- The administration reads use local evidence only and never call the provider. The one
  administration command, requesting a bounded synchronization (#248), reuses this
  authority rather than a second role: whoever reads acquisition evidence is the person who
  acts on it, and the single-owner deployment has no separation-of-duties need. As a
  cookie-authenticated state change it also requires the session-bound CSRF token and
  same-origin browser metadata, and an anonymous request is `401` before CSRF is judged. It
  answers before any provider call. Stopping or abandoning one run (#342) reuses the same
  authority by the owner's decision in that issue, under the same CSRF, same-origin and
  `401`-first rules: whoever may start a run is whoever may end it.
- Authorities are fixed at sign-in for the session's life. A grant applies from the next
  sign-in; a revocation applies to new sessions, and the owner ends existing ones with the
  ADR-0027 revocation procedure.
- The realm import declares the role and mapper for new realms; an existing realm is
  aligned and its holders managed by a private-dev operator command.

## Alternatives

- **Realm role or group:** rejected for now; a client role keeps the vocabulary scoped to
  the one client that consumes it and out of other clients' tokens.
- **Read roles from the access token:** rejected; the BFF does not consume its access token
  as a resource server, and an ID-token claim needs no further validator.
- **Allowlist of subjects in application configuration:** rejected; it duplicates identity
  administration in deployment configuration and needs a release to change.
- **Keep the reads on the management port:** rejected by #247; it needs SSH and gives the
  browser no safe path.
- **Probe the administration API to decide navigation:** rejected; it costs a request per
  page and still discloses the same fact to the same person.
- **A separate synchronization role for #248:** rejected for now; the one owner would hold
  both, and a second role would add a realm declaration, a mapper value and an alignment
  step without a distinct holder.

## Consequences

- The production and local realm imports, the private-dev alignment command, the identity
  gate and the BFF agree on one role name and one claim name.
- A role change does not reach an open session; the owner revokes sessions when access
  must end at once.
- The session representation gains an optional capability list, an additive contract
  change.
- Further operator capabilities need their own role or an explicit decision to reuse this
  one, as #248 and #342 record above, plus CSRF protection for any state change.

## Revisit when

- A second kind of operator or a delegated, non-owner administrator is needed.
- Keycloak is replaced, or the BFF stops validating its own ID token.
- Role changes must apply to open sessions without revoking them.
