# ADR-0007: Use Keycloak as the initial identity provider

- **Status:** Accepted
- **Date:** 2026-08-03
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Related architecture:** [ADR-0003](0003-use-a-same-origin-bff-and-http-json-api.md)
- **Related platform:** [Learning MVP platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md)

## Context

ADR-0003 delegates authentication to a standards-based provider and keeps OAuth/OIDC
tokens server-side in the BFF, but it does not select a product. The initial provider
must support OpenID Connect Authorization Code with PKCE, logout, reliable issuer and
subject claims, local/container development, ARM64 hosting, and a 0 EUR private dev
environment.

[Keycloak](https://www.keycloak.org/securing-apps/oidc-layers) is an open-source,
OIDC-certified identity and access-management product. Its official container can run
locally and remotely, and its production guidance supports PostgreSQL persistence.

## Decision

Use Keycloak as the initial identity provider, operated as a separate container/process
from the application in local and `dev` environments.

Configure one project realm and one confidential BFF client. Use Authorization Code
with PKCE and OpenID Connect discovery. The BFF keeps tokens server-side and maps the
validated `issuer + subject` pair to product `UserId`; email and browser-supplied owner
identifiers are never authoritative.

Keycloak uses its own PostgreSQL database, role, and credentials on the environment's
PostgreSQL server. Realm/client configuration required for reproducibility is exported
or represented as reviewed configuration without secret values. Bootstrap credentials
and client secrets come from the environment secret mechanism.

Keycloak is an external logical identity boundary even when co-hosted on the same
private dev VM. Product authorization remains in the application.

## Alternatives considered

### Managed identity SaaS

Auth0, Microsoft Entra External ID, Amazon Cognito, and similar services reduce
operation but add changing free-tier limits, external account coupling, and less
control over local/offline fidelity. They remain valid replacements if operating
Keycloak becomes disproportionate.

### Implement credentials in the application

This would add password storage, verification, recovery, and abuse responsibilities
outside the product's learning scope and security posture.

### Browser-only OAuth client

This would place tokens in the browser and contradict the accepted BFF boundary.

## Consequences

### Positive

- The product uses a current, portable, enterprise-relevant OIDC provider at no
  licence cost.
- Local and remote environments can exercise the same issuer/client behaviour.
- Identity-provider replacement remains possible through standard OIDC boundaries.
- Credential implementation stays outside the application.

### Negative

- The owner must patch, configure, back up, and observe Keycloak.
- Keycloak consumes part of the constrained OCI VM and adds startup/readiness
  dependencies.
- Co-hosting does not provide independent availability.

## Risks and mitigations

- **Misconfiguration:** version realm/client configuration, validate exact redirect
  URIs, issuer, audience, PKCE, logout, cookie, and proxy settings.
- **Administrative exposure:** keep the admin console on private Tailscale access and
  use strong rotated credentials.
- **Identity loss:** back up Keycloak PostgreSQL state and reproducible configuration.
- **Resource pressure:** set measured JVM/container limits and avoid unused extensions.
- **Product coupling:** use OIDC discovery and standard claims; keep Keycloak roles and
  proprietary concepts outside domain/API contracts.

## Follow-up actions

- Pin the baseline-selected Keycloak 26.7 image by version or digest and test its
  `linux/arm64` support in the walking skeleton before remote provisioning.
- Define realm, BFF client, redirect/logout URIs, token/session lifetimes, and private
  administration policy.
- Add integration tests for PKCE, callback validation, session rotation, CSRF, logout,
  expiry, replay prevention, and `issuer + subject` mapping.
- Revisit the provider if resource/operational cost, public release, or external-user
  requirements make a managed service safer.
