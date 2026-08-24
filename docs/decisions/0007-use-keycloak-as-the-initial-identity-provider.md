# ADR-0007: Use Keycloak as the initial identity provider

- **Status:** Accepted
- **Date:** 2026-08-03
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP

## Context

The product needs standards-based login and session learning without implementing
password storage or depending on a paid identity SaaS. Identity must remain separate
from the product's user and authorization model.

## Decision

- Run Keycloak as the initial OIDC provider in both local and private-dev
  environments.
- Use Authorization Code with PKCE through the same-origin BFF described in
  [ADR-0003](0003-use-a-same-origin-bff-and-http-json-api.md).
- Link a product user to stable `(issuer, subject)` identity; do not use email or
  display name as identity keys.
- Keep credentials, provider tokens, realm configuration and Keycloak schema outside
  domain/application code and outside the product database ownership boundary.
- Export/recreate realm configuration and back up Keycloak state as part of platform
  recovery.

## Alternatives considered

- **Managed identity SaaS:** deferred because free-tier terms and external lock-in do
  not improve the private learning release.
- **Application-managed passwords:** rejected as unnecessary security-sensitive
  scope.
- **Browser-only OAuth client:** rejected because it conflicts with the BFF token
  boundary.

## Consequences

The application gets a mature OIDC implementation and keeps credential handling out
of product code. It also owns another stateful service, upgrade path and recovery
procedure, and Keycloak availability is required for new logins.

## Reconsider when

Revisit if release mode, availability requirements, maintenance burden or a concrete
enterprise identity integration justifies another provider.
