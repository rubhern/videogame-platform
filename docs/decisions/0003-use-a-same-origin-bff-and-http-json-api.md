# ADR-0003: Use a same-origin BFF and HTTP/JSON API

- **Status:** Accepted
- **Date:** 2026-07-30
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP

## Context

The browser needs product APIs and OIDC login without holding reusable provider
tokens. The MVP does not need independent frontend deployment or an API-management
product, and same-origin delivery avoids unnecessary CORS and token exposure.

## Decision

- Serve the packaged React application and `/api/**` from one origin.
- Use an HTTP/JSON BFF boundary described contract-first by OpenAPI.
- Use OIDC Authorization Code with PKCE through the backend.
- Store the browser session in an opaque, `Secure`, `HttpOnly`, same-site cookie;
  tokens remain server-side.
- Protect unsafe requests against CSRF and rotate/invalidate sessions at the
  appropriate authentication boundaries.
- Identify the product user by stable issuer and subject, not mutable profile data.
- Keep request context replay-safe: carry stable identity and reload authoritative
  authorization state when required.
- Keep transport models and security mechanics outside domain and application code.

Exact routes and payloads belong to
[`openapi.yaml`](../architecture/api/openapi.yaml); cross-cutting behavior belongs to
the [API conventions](../architecture/api/api-conventions.md).

## Alternatives considered

- **Browser-held OAuth tokens/direct API calls:** rejected because it expands token
  exposure and browser security responsibilities.
- **Independent frontend and BFF deployment:** deferred until independent delivery
  has demonstrated value.
- **API manager from the first slice:** deferred until external consumers, policy or
  traffic justify it.

## Consequences

Authentication and provider credentials remain server-side and the browser has a
simple same-origin contract. The backend also owns session state, CSRF protection and
static delivery, and horizontal scaling requires a shared or otherwise replay-safe
session design.

## Reconsider when

Revisit for external API consumers, genuinely independent frontend deployment,
mobile/native clients, or measured policy and traffic needs that justify an API
gateway or manager.
