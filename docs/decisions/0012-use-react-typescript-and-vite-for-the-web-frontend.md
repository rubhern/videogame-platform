# ADR-0012: Use React, TypeScript, and Vite for the web frontend

- **Status:** Accepted
- **Date:** 2026-08-04
- **Decision owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP
- **Technology baseline:** [Learning MVP technology baseline](../architecture/technology/mvp-technology-baseline.md)
- **Solution architecture:** [Learning MVP solution architecture](../architecture/mvp-solution-architecture.md)
- **API conventions:** [Learning MVP API conventions](../architecture/api/api-conventions.md)

## 1. Context

The approved product requires a responsive Spanish-first browser experience for
release discovery, catalogue search, game details, authentication at the rating
boundary, rating management, and `Mis puntuaciones`.

The frontend uses the same origin as the server-side BFF/API and is initially packaged
with the modular-monolith deployment. The product has no validated SSR, SEO, public
landing-page, or independent frontend deployment requirement.

The frontend baseline must provide strong typing, rapid development feedback,
contract-driven API integration, accessible component testing, and a clear separation
between server state and local UI state.

## 2. Decision drivers

- Mature ecosystem and owner familiarity.
- Strong TypeScript support.
- Good component composition for a design that will evolve from a prototype.
- Fast local development and production builds.
- Compatibility with generated OpenAPI clients.
- Straightforward browser and component testing.
- Same-origin SPA delivery without introducing SSR infrastructure.
- Minimal initial client-state complexity.
- Long-term ability to extract independent frontend delivery if justified later.

## 3. Considered options

### Option A — React, TypeScript, and Vite

**Benefits**

- Mature component ecosystem.
- Strong TypeScript and testing support.
- Vite provides a focused build tool without imposing server rendering.
- Fits same-origin static asset delivery.
- TanStack Query cleanly models server state.

**Costs**

- Architectural decisions such as routing, state, forms, and data access must be made
  explicitly.
- Client-rendered navigation requires deliberate loading, error, and accessibility
  design.

### Option B — Next.js or another React full-stack framework

**Benefits**

- Integrated routing, SSR, server components, data loading, and deployment patterns.
- Useful for public SEO-sensitive products.

**Costs**

- Creates a second server-side application model next to the approved Java BFF.
- Adds rendering and deployment complexity without a validated requirement.
- Can blur ownership between Java application APIs and framework server functions.

### Option C — Angular

**Benefits**

- Opinionated enterprise framework with integrated routing, forms, and dependency
  injection.
- Strong TypeScript foundation.

**Costs**

- Higher framework surface for the current owner and scope.
- No demonstrated product benefit over React for this MVP.

### Option D — Server-rendered templates from Spring

**Benefits**

- One runtime and simple initial deployment.
- Minimal separate frontend toolchain.

**Costs**

- Less aligned with the interactive prototype and intended frontend learning.
- Makes rich client behaviour and component reuse less natural for the planned
  product evolution.

## 4. Decision

Use:

```text
Node.js 24 LTS
npm with package-lock.json
TypeScript strict mode
React 19.2.x
Vite 8.1.x
React Router
TanStack Query
Tailwind CSS 4.x
openapi-typescript 7.x
openapi-fetch
Vitest
React Testing Library
Playwright
```

The application is initially a client-rendered SPA. Its production assets are built
in CI and packaged into the same OCI image as the BFF/API and modular monolith.

## 5. State and integration policy

### 5.1 Server state

TanStack Query owns:

- catalogue and release queries;
- game details;
- session discovery;
- personal rating reads;
- rating mutations and invalidation;
- loading, retry, caching, and stale state appropriate to each API operation.

API caching remains subordinate to HTTP semantics and the product's freshness rules.
TanStack Query does not hide server errors or invent availability.

### 5.2 Local UI state

React component state and context own:

- open or closed UI elements;
- temporary filters before submission;
- selected rating before confirmation;
- navigation presentation state;
- other short-lived non-authoritative state.

Zustand or another global client store is deferred until concrete cross-screen state
cannot be handled clearly through React and TanStack Query.

### 5.3 API integration

- The OpenAPI contract is the source of truth.
- openapi-typescript-generated contract types and openapi-fetch are isolated behind a
  small frontend API module.
- UI components do not build raw provider URLs or depend on IGDB types.
- Authentication tokens never enter JavaScript-accessible storage.
- The browser sends the opaque session cookie automatically on same-origin requests.
- CSRF material comes from the session resource and is held only as needed in memory.
- Personal and session responses are not persisted to local storage.

## 6. Rendering and delivery policy

- Client-side rendering is the initial model.
- Vite builds static assets.
- The backend serves the frontend entry point and assets from the same origin.
- Browser routes use a documented fallback to the SPA entry point.
- `/api` and `/auth` routes remain server-owned and are never captured by SPA routing.
- Content Security Policy permits only approved sources, including the allowlisted
  IGDB image CDN where required.
- Public API responses may use HTTP caching; session and personal responses remain
  `no-store`.

SSR, React Server Components, edge rendering, and an independent frontend deployment
remain deferred.

## 7. Testing and quality policy

- TypeScript strict mode is mandatory.
- ESLint or equivalent linting is configured with a small reviewed rule set.
- Vitest covers utility and state behaviour.
- React Testing Library tests user-visible component behaviour rather than component
  internals.
- Playwright covers the primary end-to-end journey and degraded states.
- Accessibility checks cover keyboard interaction, semantics, labels, focus, and
  critical contrast rules.
- The build fails on type errors.
- Contract types are regenerated with openapi-typescript and validated with
  `tsc --noEmit` in CI when OpenAPI changes.
- Bundle size is measured before performance budgets become blocking.

## 8. Consequences

### Positive

- Fast developer feedback and production builds.
- Strong type safety across the frontend and generated API integration.
- Mature component and testing ecosystem.
- No second server-side rendering runtime.
- Clear separation between server state and local UI state.
- Same-origin delivery stays operationally simple.

### Negative

- Client-side rendering provides limited SEO and initial HTML content.
- The team must define conventions that a more opinionated framework might supply.
- Generated API clients require controlled mapping to avoid leaking contract details
  throughout the UI.
- React and npm ecosystem updates require active dependency governance.

### Accepted

- The private MVP does not optimize for public search-engine indexing.
- Frontend and backend are versioned and deployed together initially.
- Independent scaling and deployment of the frontend are deferred.

## 9. Implementation verification

Acceptance authorizes implementation. The walking skeleton must provide the first
evidence, and later slices extend it:

- Node 24 LTS and npm reproduce the build from `package-lock.json`;
- React 19.2 and Vite 8.1 build a production application;
- TypeScript strict mode passes;
- openapi-typescript generates the complete OpenAPI 3.1.2 contract, including
  `oneOf` schemas, and the generated types pass `tsc --noEmit`;
- openapi-fetch calls a same-origin API endpoint through the product-facing layer;
- browser routing does not intercept `/api` or `/auth`;
- component tests and one Playwright journey pass in CI;
- the assets are packaged into and served by the application OCI image;
- session and personal data are not written to browser storage.

A blocking Node, Vite, generated-client, browser-support, or multi-architecture image
incompatibility reopens this ADR.

## 10. Reconsideration triggers

- Public SEO or server-rendered performance becomes a validated product requirement.
- Independent frontend deployment provides measurable organizational or operational
  value.
- Client state becomes complex enough to justify a global store.
- React or Vite support no longer meets security or maintenance needs.
- Native mobile or another client changes the frontend architecture materially.

## 11. Official references

- [Node.js release schedule](https://nodejs.org/en/about/previous-releases)
- [React versions](https://react.dev/versions)
- [React versioning policy](https://react.dev/community/versioning-policy)
- [Vite 8.1 announcement](https://vite.dev/blog/announcing-vite8-1)
- [OpenAPI TypeScript](https://openapi-ts.dev/)
- [openapi-fetch](https://openapi-ts.dev/openapi-fetch/)

## 12. Change history

| Date | Status | Change |
|---|---|---|
| 2026-08-03 | Proposed | Initial decision selecting React 19.2, TypeScript, Vite 8.1, Node 24 LTS, npm, and the initial frontend support stack. |
| 2026-08-04 | Accepted | Approved the frontend baseline, selected openapi-typescript/openapi-fetch for the OpenAPI 3.1.2 boundary, and moved executable proof to implementation gates. |
