# ADR-0012: Use React, TypeScript and Vite for the web frontend

- **Status:** Accepted
- **Date:** 2026-08-04
- **Owner:** Ruben Hernandez
- **Scope:** Initial web frontend

## Context

The private MVP needs an interactive, accessible web client aligned with the
prototype and one same-origin deployable. Public SEO, server rendering and
independent frontend scaling are not validated requirements.

## Decision

- Use Node.js 24/npm, strict TypeScript, React 19.2 and Vite 8.1 for a client-rendered
  SPA.
- Use React Router for browser navigation, TanStack Query for server state and the
  smallest local React owner for transient UI state.
- Use Tailwind CSS for styling; do not add a global state or component system without
  a demonstrated need.
- Generate disposable TypeScript contract types from OpenAPI and isolate
  `openapi-fetch` behind a small API layer. UI code never depends on provider types.
- Keep authentication tokens and personal/session data out of browser storage; use
  the opaque same-origin session cookie and in-memory CSRF material.
- Package production assets into the backend OCI image. `/api` and `/auth` remain
  server-owned and browser routes use the SPA fallback.
- Test behavior with Vitest/React Testing Library and the primary journey with
  Playwright, including accessibility and degraded states at appropriate seams.

Exact dependencies and scripts belong to npm manifests; current operating commands
belong to the [frontend README](../../frontend/README.md).

## Alternatives considered

- **Next.js or another full-stack React framework:** deferred because SSR/RSC and a
  second server runtime are not required.
- **Angular:** viable but adds framework breadth without better fit for the approved
  journey.
- **Spring-rendered templates:** rejected because they weaken the intended rich-client
  learning and interaction model.

## Consequences

The client gets fast feedback, strict contract integration and a mature component
ecosystem without another runtime. Client rendering limits SEO/initial HTML, and the
npm ecosystem plus generated boundary require active maintenance.

## Reconsider when

Revisit for validated public SEO/server-rendering needs, valuable independent
deployment, proven cross-screen client-state complexity, native clients or loss of
security/maintenance support.
