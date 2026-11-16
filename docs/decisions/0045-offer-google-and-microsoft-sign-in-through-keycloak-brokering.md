# ADR-0045: Offer Google and Microsoft sign-in through Keycloak brokering

- **Status:** Proposed
- **Date:** 2026-11-14
- **Owner:** Ruben Hernandez
- **Scope:** Which external identity providers Gameómetro accepts, how Keycloak brokers them, how a
  provider identity becomes or joins a Gameómetro account, and how the providers are configured and
  turned off in each environment
- **Issue:** [#548](https://github.com/rubhern/videogame-platform/issues/548), resolving the spike
  [#540](https://github.com/rubhern/videogame-platform/issues/540)
- **Related:** [ADR-0003](0003-use-a-same-origin-bff-and-http-json-api.md),
  [ADR-0007](0007-use-keycloak-as-the-initial-identity-provider.md),
  [ADR-0027](0027-revoke-and-end-bff-sessions-in-the-application.md),
  [ADR-0037](0037-align-local-and-private-dev-with-production.md),
  [ADR-0044](0044-run-the-unverified-account-cleanup-unattended-on-the-production-vps.md)

## Context

Registering requires an email, a password and an email verification. A one-click entry with an
account people already have removes most of that friction. Having a Gmail or Outlook address is not
the same as signing in through Google or Microsoft: the feature is federation, where the provider
proves the person and Keycloak decides which Gameómetro account that person gets.

Product identity is `(issuer, Keycloak subject)` ([ADR-0007](0007-use-keycloak-as-the-initial-identity-provider.md)).
The BFF never sees a provider token ([ADR-0003](0003-use-a-same-origin-bff-and-http-json-api.md)). A brokered
person is still a Keycloak user with its own subject. The BFF, its session, CSRF and the ratings'
ownership therefore need no change, as long as Keycloak never attaches a provider identity to the
wrong account.

The owner decided on 2026-11-14, without a separate research record:

| Provider | Decision | Reason |
|---|---|---|
| Google | Adopt | Most used account; built-in Keycloak provider; free with `openid email` |
| Microsoft, personal accounts | Adopt | Same account as Xbox, Outlook and Hotmail; built-in provider; free Entra registration |
| Apple | Defer | Paid developer programme (a purchase gate), a client-secret JWT that expires within six months, private-relay addresses |
| Discord, Steam, Twitch | Reject for now | No built-in provider: a community extension, or OpenID 2.0 without an email |

The owner also chose how much to trust each provider's email: Google's verified email is trusted;
Microsoft's is verified once by Gameómetro. With trust, Keycloak's stock email-verification linking
step attaches the provider to an existing account with the same email without any proof. That is
exactly the email-only linking this decision forbids.

## Options considered

1. **Stock first-broker-login flow.** It offers linking by email or by password, and with a trusted
   email it links automatically on a matching address. It is rejected for that reason.
2. **No trust for either provider.** Every new Google person would verify an email Google already
   verified. This is safe but adds the friction the feature removes.
3. **A reviewed flow without email-based linking**, trusting Google only. Chosen.
4. **A second sign-in subsystem or an OAuth client in React.** This is excluded by
   [ADR-0003](0003-use-a-same-origin-bff-and-http-json-api.md) and [ADR-0007](0007-use-keycloak-as-the-initial-identity-provider.md).

## Decision

- **Brokering.** Keycloak's built-in `google` and `microsoft` providers. Microsoft is restricted to
  personal accounts (`tenantId=consumers`). Both use `syncMode=IMPORT`, so a later provider change
  never rewrites the account's email. Neither stores provider tokens or authenticates by default.
  Scopes are the minimum: `openid email` for Google, `User.Read` for Microsoft. Google trusts the
  email; Microsoft does not.
- **One reviewed first-broker-login flow**, `gameometro first broker login`:
  1. Review profile data only when it is missing.
  2. Create the account only when its email is unused.
  3. Otherwise confirm, then link only after the existing account's password. The password form
     also offers the account's other linked provider.
  4. Finally, an account the flow has just created must accept the terms. A small authenticator in
     the Keycloak image (`gameometro-brokered-terms`) adds the `TERMS_AND_CONDITIONS` required
     action only when Keycloak's own create-user step reports a new account. The registration form
     stays the only acceptance for email registrants, so the action is enabled but never a default.

  The flow has no email-verification linking, automatic linking or link-override step.
- **Outcomes.**
  - A new person gets a new account and `UserId`.
  - A returning person gets the same account.
  - An existing person keeps their `UserId` and ratings and gains a second way in.
  - A person with an unverified Microsoft email must verify it. If they never do, the 30-day
    cleanup of [ADR-0044](0044-run-the-unverified-account-cleanup-unattended-on-the-production-vps.md)
    deletes the account.
  - Declining the terms returns to the BFF as a cancellation. Cancelling at the provider returns to
    the sign-in page with email and password still available.
- **Turning a provider off disables it and never deletes it.** Deleting a provider would drop every
  linked identity. A person who only ever used a disabled provider recovers access by resetting the
  password for the account's email.
- **One reviewed actor** (`deploy/public-production/bin/configure-social-sign-in`) reconciles the
  terms action, the flow and both providers through the Admin API. It is the same for local,
  private-dev and production. It never touches accounts, clients or other realm settings, and it
  receives credentials and the administrator password on stdin only.
  - In production the owner runs `scripts/production_social_sign_in.py` on the host. It applies the
    same admission as opening registration: no unfinished upgrade, an open gate, no identity fence.
  - Production credentials are root `0600` files under `/etc/gameometro/production/social-sign-in/`.
    They are not Compose secrets or runtime inputs, so no in-place upgrade is needed to add, rotate
    or remove them.
  - The production realm import stays provider-free: a provider is configuration the owner enables.
- **The edge** routes only `/realms/gameometro/broker/google/*` and
  `/realms/gameometro/broker/microsoft/*`. The password step of linking shares the sign-in rate limit.
- **The operator's personal-data export** includes each linked provider and the identifiers it gave
  the account.

## Consequences

- Google and Microsoft learn that the person uses Gameómetro, and Gameómetro receives the provider's
  account identifier and email. The privacy text and the
  [data-protection record](../development/data-protection.md) record both before public use.
- The Keycloak image ships a second custom provider. The theme overrides `register.ftl`, `terms.ftl`,
  `login-idp-link-confirm.ftl` and `social-providers.ftl`, which must be re-copied from the parent
  theme on a Keycloak upgrade.
- Google and Microsoft app registrations, consent screens and their secrets are owner-held
  operational dependencies with their own rotation. A provider outage or a lost provider account
  leaves email and password, and password recovery, as the way in.
- Unlinking from the account console is not offered: the console is not routed. An operator can
  remove a link through the Admin API on request.

## Revisit when

- Apple sign-in becomes worth its yearly cost, or a native app makes it mandatory.
- Gaming providers (Discord, Steam, Twitch) gain built-in Keycloak support.
- Self-service unlinking is wanted in the product.
- Keycloak's first-broker-login authenticators or required-action contract change.
