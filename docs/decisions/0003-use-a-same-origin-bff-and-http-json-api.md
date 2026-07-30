# ADR-0003: Use a same-origin BFF and HTTP/JSON API

- **Status:** Accepted
- **Date:** 2026-07-30
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Related architecture:** [Learning MVP solution architecture](../architecture/mvp-solution-architecture.md)

## Context

The MVP has one browser client and one backend. Public catalogue reads require no
authentication, while rating and personal-data operations require delegated
authentication and ownership derived from a trusted principal. The product must not
implement credential storage or expose identity-provider tokens to browser code.

The browser journey is request/response oriented and benefits from an explicit,
provider-independent contract. There is no current need for multiple external API
consumers, independently routed services, centralized quotas, a developer portal, or
another dedicated API-management deployable.

OAuth security guidance recommends Authorization Code with PKCE and discourages the
implicit grant. Current browser-based application guidance identifies the
server-side BFF as the strongest of the common browser patterns because tokens
remain outside the browser:

- [RFC 9700: Best Current Practice for OAuth 2.0 Security](https://www.rfc-editor.org/rfc/rfc9700)
- [RFC 10017: OAuth 2.0 for Browser-Based Applications](https://www.rfc-editor.org/rfc/rfc10017)

## Decision

Use one same-origin HTTPS application entry point for static frontend assets, a
server-side BFF/API adapter, and the modular-monolith application.

Use a resource-oriented HTTP/JSON API described by OpenAPI. The BFF is an inbound
adapter in the initial deployable, not an independent business service.

For delegated authentication:

- use OAuth 2.0 Authorization Code with PKCE and OpenID Connect where identity claims
  are required;
- operate the BFF as the confidential client and keep access and refresh tokens
  server-side;
- give the browser only an opaque, `Secure`, `HttpOnly`, appropriately `SameSite`
  session cookie;
- protect state-changing cookie-authenticated requests against CSRF;
- rotate, expire, and invalidate sessions on the appropriate lifecycle events;
- validate and atomically consume short-lived post-authentication return context so
  a rating command cannot be replayed;
- map validated `issuer + subject` to a stable product `UserId`, never email or a
  client-supplied owner identifier;
- keep product authorization and business validation in the application.

Defer API Management. Introduce a gateway or manager only when multiple external
consumers, versions, independently routed services, centralized policies or quotas,
a developer portal, or a bounded learning experiment justifies the additional
component. Preserve OpenAPI and stable error contracts so that later adoption does
not require redesigning the product boundary.

## Alternatives considered

### Browser-only OAuth public client

This removes server-side session handling, but exposes tokens to browser execution
context and increases the impact of browser compromise. It is not preferred for the
current personal-data boundary.

### Independently deployed BFF and frontend

This can support independent scaling or teams, but adds deployment, routing, CORS,
and operational work without a current need.

### API Manager from the first slice

This provides early experience with centralized policies and lifecycle tooling, but
adds a deployable and failure point for one client and one backend. A bounded
experiment remains available when learning value is the explicit goal.

### Direct browser-to-backend bearer tokens

This avoids cookie sessions but requires the browser to hold tokens. The stronger
server-side token boundary is preferred.

## Consequences

### Positive

- Provider tokens and credentials remain outside browser code.
- One origin avoids premature CORS and multi-deployment complexity.
- OpenAPI provides a stable, testable contract without requiring a management
  product.
- Product authorization remains explicit and testable in the backend.

### Negative

- The application owns server-side session lifecycle and CSRF controls.
- Frontend and backend initially share one deployment and scaling boundary.
- API Management learning and centralized policy tooling are deferred.

## Risks and mitigations

- **Session theft or fixation:** use secure cookie attributes, session rotation,
  expiry, logout invalidation, and integration tests.
- **CSRF:** require a documented CSRF mechanism for state-changing requests.
- **Authentication replay:** store bounded, tamper-resistant, single-use return
  context and consume it atomically.
- **Identity collision:** key the product mapping by validated issuer and subject,
  not subject alone or mutable claims.
- **Future edge coupling:** keep the OpenAPI contract and product authorization
  independent from any later gateway product.

## Follow-up actions

- Define API paths, status codes, stable error envelope, pagination, compatibility,
  security schemes, and examples in API conventions and OpenAPI.
- Select the identity provider and document issuer, audience, redirect, logout,
  session, and token-validation configuration.
- Define session persistence and CSRF implementation during implementation design;
  the MVP does not require a distributed session store.
- Add integration tests for PKCE, callback validation, session lifecycle, CSRF,
  principal mapping, logout, expiry, and replay prevention.
- Create a separate ADR before adopting API Management.
