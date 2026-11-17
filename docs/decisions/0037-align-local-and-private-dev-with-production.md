# ADR-0037: Align local and private dev with production configuration

- **Status:** Accepted
- **Date:** 2026-11-09
- **Owner:** Ruben Hernandez
- **Scope:** Product-facing runtime configuration of local development and private `dev` relative to public production; released as product version `1.0.0`
- **Related:** [ADR-0019](0019-host-private-dev-on-an-owner-managed-linux-host.md), [ADR-0025](0025-host-public-production-on-a-deferred-single-vps.md), [ADR-0033](0033-select-lite-3-and-immutable-offhost-storage.md), [ADR-0036](0036-use-protected-actions-for-production-recovery.md)

## Context

Public production has run since 2026-11-08. Local and private `dev` had drifted from it
in ways a user would notice: recurring synchronization was off, the Keycloak realm imported
for new local and private-dev realms kept a permissive identity policy (username sign-in, no
email verification, no password policy or brute-force protection, names in the registration
form), Keycloak's runtime bounds were copied by value, and private `dev` kept its own
encrypted backup, restore and rollback-assessment procedure. Behaviour proven locally or in
private `dev` therefore did not predict production behaviour.

## Decision

Local and private `dev` run production's product-facing configuration, with exactly these
owner-approved exceptions:

1. **Infrastructure.** Hosts, networks, ingress, HTTPS and certificates, published ports,
   container resource envelopes, image provenance and the observability topology may differ.
   The realm name, issuer, `sslRequired`, the BFF client and its scopes, and the mail transport
   (Mailpit locally, the relay selected explicitly in private `dev`) stay environment-specific.
2. **Synchronization.** Production runs the daily near-term and the weekly upcoming policy.
   Local and private `dev` run only the weekly upcoming policy, on the same defaults: recurring
   synchronization is enabled and the near-term cron is `-`. Without IGDB credentials a local
   scheduled run reports `SYNCHRONIZATION_DISABLED` and calls nothing.
3. **Backups.** Backups and every related process (encryption, verification, retention,
   restore, restore rehearsal, rollback assessment and host-loss restore) exist only in
   production. Private-dev identity and rating data has no recovery guarantee.
4. **Lifecycle and verification.** Deployment, promotion, smoke, acceptance and validation
   procedures, and owner-only experiment controls, may differ.

Everything else is shared by reference, not copied: the realm import every new local and
private-dev realm starts from carries the reviewed production identity policy (the same subset
`configure-keycloak-identity` applies to an existing realm, which local startup now applies in
full), and both environments read production's Keycloak runtime policy file directly.
Executable checks own the parity: `scripts/validate-private-dev-runtime.sh`,
`scripts/test-production-realm.py` and `scripts/test-private-dev-identity-commands.py`.

## Alternatives considered

- **Keep the permissive local identity policy for convenience.** Rejected: identity behaviour
  is user-visible and was the largest silent difference.
- **Keep private-dev backups.** Rejected by the owner: private `dev` holds no data whose loss
  matters enough to justify a second backup system to operate and test.
- **Run the daily synchronization everywhere.** Rejected: it multiplies provider use for
  environments that do not need same-day catalogue freshness.

## Consequences

A new difference needs an owner decision and an update to this record. Existing local and
private-dev realms are aligned by `configure-keycloak-identity`; accounts keep their IDs,
credentials and ratings, and self-registration now verifies the email before the password is
chosen. Losing the private-dev host loses its identity and rating data; recovery is a
foundation rebuild, a fresh deployment and a catalogue resynchronization.

## Reconsider when

Private `dev` starts holding data whose loss would matter, production gains a capability
these environments cannot reproduce, or provider limits make the weekly policy costly.
