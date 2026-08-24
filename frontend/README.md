# VideoGame Platform frontend

The frontend is a client-rendered React/TypeScript SPA for the approved same-origin
BFF/API. It currently renders a typed recent-releases shell and placeholder game
route. Filters, catalogue search, game details, ratings, `Mis puntuaciones`, and
provider synchronization UI remain later slices.

## Install and verify

Run from the repository root with Node.js 24 and npm 11:

```bash
npm ci
npm run frontend:verify
```

`frontend:verify` regenerates OpenAPI types, lints, type-checks, runs component tests,
builds production assets, and verifies that Playwright can discover the browser
tests. Exact dependency versions and scripts live in
[`package.json`](package.json) and the root lock file.

## Develop locally

Start the backend on port 8080, then:

```bash
npm run frontend:dev
```

Vite serves `http://localhost:5173` and proxies `/api`, `/auth`, and `/actuator` to
the backend. Those paths remain server-owned and must not become client routes.

Production assets are written to ignored `frontend/dist/`. Build the deployable
same-origin JAR with `bash scripts/package-application.sh`; validate the real packaged
browser path with `bash scripts/validate-browser.sh`.

## Structure and ownership

| Path | Responsibility |
|---|---|
| `src/app/` | Providers, router, query client, and application shell |
| `src/features/` | Product capabilities and their API/view-model/UI code |
| `src/pages/` | Route-level composition |
| `src/shared/api/` | Generated contract and product-facing transport boundary |
| `src/styles/` | Global Tailwind entry and shared visual foundations |
| `src/test/` | Shared component-test setup |
| `tests/` | Packaged browser journeys |

Keep feature behaviour close to its feature. Do not create a design system, global
state store, or generic abstraction without demonstrated reuse or ownership value.

## API, routing, and state

[`docs/architecture/api/openapi.yaml`](../docs/architecture/api/openapi.yaml) is the
wire contract. `openapi-typescript` generates
`src/shared/api/generated/schema.d.ts`; never edit it. `openapi-fetch` remains behind
product-facing API functions and hooks so generated transport types do not spread
through components. Follow the [OpenAPI workflow](../docs/development/openapi.md).

React Router owns browser navigation. TanStack Query owns server state, caching,
loading, and invalidation. Component state owns transient local interaction state;
URL parameters own navigable/shareable state when safe. OAuth tokens and personal
responses must not be stored in browser storage. Same-origin requests preserve the
BFF cookie and CSRF contract.

## Accessibility and testing

Every implemented state must be usable with semantic HTML, keyboard navigation,
visible focus, accessible names, and understandable loading/empty/error feedback.
Component tests use Vitest and React Testing Library; Playwright owns complete
packaged journeys and axe checks. Test observable behaviour rather than component
internals.

The project-specific frontend skill and
[ADR-0012](../docs/decisions/0012-use-react-typescript-and-vite-for-the-web-frontend.md)
define the approved framework boundary. Next.js, SSR, React Server Components,
another state library, and a component library remain deferred until a concrete need
is approved.
